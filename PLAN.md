# Cyclocomp — Product & Technical Plan

An Android app that turns an old phone into a bike computer (cyclocomp), plus a
health/training layer: import GPX and Huawei Health screenshots, store everything
in a private GitHub data repo, and use that history to tune your training plan.

Status: **PLAN — nothing implemented yet. Review and answer the open questions at the bottom.**

---

## 1. Goals

1. Old phone mounted on the handlebar works as a reliable, battery-frugal bike computer.
2. Record rides with the usual cyclocomp metrics and export them (GPX/FIT).
3. Import external data: GPX files and Huawei Health detail screenshots.
4. Keep all data in a git repo (cloud storage, versioned, yours).
5. Use the accumulated data to adjust your sport plan / targets.

Non-goals (v1): social features, segments/leaderboards, live tracking, iOS, watch app.

## 2. Stack (matches the esp32-display-companion decisions)

- **Native Kotlin**, Jetpack Compose UI, min SDK 26 (revisit once we know the phone model).
- **Room** (SQLite) as the local source of truth; **WorkManager** for sync/import jobs.
- **Foreground service** for ride recording (survives screen off).
- **FusedLocationProvider** (fallback to raw `LocationManager` if the old phone lacks Play Services).
- **osmdroid** for maps (no API key, same choice as the ESP32 project) with an offline tile cache.
- **Routing**: reuse your self-hosted OSRM (runtime-configurable address, https only). Caveat: cycling needs a *bicycle* profile; the current server may be running the car profile.
- **BLE sensors**: standard GATT profiles — Heart Rate (0x180D), Cycling Speed & Cadence (0x1816), Cycling Power (0x1818).
- **CI**: GitHub Actions builds the APK, path-filtered, gitleaks secret scan, unit tests. Noreply git identity.

## 3. Feature list

### A. Ride recording (core)
- Start / pause / resume / stop / lap; **auto-pause** when speed ~0.
- Metrics: speed (current/avg/max), distance, moving + elapsed time, elevation gain/loss, grade, heart rate, cadence, power (if sensors), calories estimate, clock/battery.
- GPS smoothing + outlier rejection; barometer-assisted elevation when the phone has a pressure sensor.
- Crash-safe: points written to Room continuously; recover an interrupted ride on next launch.
- Export: **GPX** (v1), **FIT** (later).

### B. Ride screen (the cyclocomp UI)
- Big-number data pages, swipeable; user-configurable fields per page (1–8 fields).
- High-contrast day/night themes, glove-friendly large touch targets.
- Always-on option with **dim/black screen mode** to save battery.
- Alerts: HR zone, speed, distance/time interval, off-route, low battery.

### C. Navigation
- Follow an imported GPX course with breadcrumb line, distance-to-go, ETA.
- Off-route detection + reroute via OSRM (same idea as `RouteTracker` in the ESP32 app).
- Destination picker (tap on map) -> OSRM route -> turn-by-turn cues (visual + optional beep/TTS).
- Offline map tiles only from a self-hosted/permitted source (public OSM/CARTO servers forbid bulk download).

### D. Sensors
- BLE scan/pair/remember sensors; auto-reconnect; battery level display.
- Optional later: mirror data to the ESP32 display over the existing BLE work.

### E. History
- Ride list, ride detail (map, charts for speed/HR/elevation, laps, splits), personal records, weekly/monthly totals.

### F. Health data import
- **GPX import** (other devices/apps): parse tracks, HR/cadence extensions, dedupe against existing rides.
- **Huawei Health photos**: pick screenshots of detail screens (workout summary, HR, sleep, resting HR, steps, SpO2, stress, HRV).
  - Pipeline: image -> text -> structured JSON -> **user confirms/edits** -> saved.
  - OCR options (decision needed): on-device **ML Kit** (offline, free, needs per-screen layout parsing) vs. a **vision LLM** (Claude API: far better on varied layouts, but images leave the phone and it costs money).
  - Original image is always kept; the parsed record links to it so extraction can be re-run.
- Note: Huawei Health Kit needs developer-account approval and Huawei often doesn't write to Health Connect, so screenshots + GPX is the pragmatic path. Huawei's "request my data" export (CSV/JSON) can be a later import source.

### G. Cloud storage = a private git repo
- Separate **private data repo** (e.g. `cyclocomp-data`), never the app repo.
- Layout:
  ```
  rides/2026/2026-11-03T0712_morning-ride.gpx
  rides/2026/2026-11-03T0712_morning-ride.json   # summary + metrics
  health/2026/2026-11-03.json                    # sleep, resting HR, HRV, steps...
  health/raw/2026/2026-11-03_sleep.jpg           # original screenshots (downscaled)
  plan/plan.json                                 # current plan + target history
  plan/reviews/2026-W44.md                       # weekly auto-review
  ```
- Sync via the GitHub REST API from a WorkManager job: offline queue, retry, one commit per batch; no conflicts because each file name is unique.
- Auth: fine-grained PAT (Contents read/write, **that repo only**) in Keystore-backed encrypted storage; entered at runtime, never in the repo/APK.
- Photos downscaled before upload; stay under GitHub limits (100 MB/file, repo well under a few GB). Git LFS optional later.
- Privacy: health data is sensitive — private repo only, `allowBackup=false`.

### H. Training plan tuning
- **Metrics from your data** (no power meter assumed): HR-based load (TRIMP), weekly volume/time/elevation, acute vs. chronic load, HR-at-given-speed trend (fitness proxy), resting HR / HRV / sleep as recovery signals.
- **Plan model**: a goal (event/distance/date or "X km/week"), weekly template (easy/long/interval/rest), targets stored in `plan/plan.json`.
- **Tuning engine, two layers**:
  1. *Rule-based (offline, deterministic)*: ramp-rate cap, recovery week insertion, scale next week by completion %, drop intensity when resting HR is up or sleep is poor.
  2. *Optional LLM coach* (Claude API, your own key, off by default): sends a compact summary of the last N weeks, returns a proposed plan diff + explanation; you accept or reject. Raw GPS tracks and photos are not sent unless you opt in.
- Weekly review screen: planned vs. actual, trends, "next week" proposal.

## 4. Architecture sketch

```
app/
  recording/   RideService, LocationSource, SensorHub (BLE), RideRecorder, AutoPause
  ride/        Compose screens: RideScreen, DataFieldConfig
  nav/         CourseFollower, OsrmClient, OffRouteDetector
  data/        Room (Ride, TrackPoint, Lap, HealthRecord, PlanEntry), repositories
  importer/    GpxParser, HealthImageExtractor (OCR/LLM), ImportReviewScreen
  export/      GpxWriter, (FitWriter later)
  sync/        GitHubDataRepoClient, SyncWorker, TokenStore
  planner/     LoadMetrics, PlanEngine (rules), CoachClient (optional LLM)
  ui/          history, ride detail, health, plan, settings
```

## 5. Milestones

| # | Milestone | Outcome |
|---|-----------|---------|
| M0 | Repo + CI skeleton | Compose app builds an APK in CI, gitleaks, unit tests |
| M1 | Ride recording MVP | Foreground service, GPS, live speed/distance/time, save ride, GPX export |
| M2 | Cyclocomp UX | Configurable data pages, auto-pause, dim mode, history + ride detail |
| M3 | BLE sensors | HR / cadence / power |
| M4 | Data repo sync | Private repo upload, offline queue, token storage |
| M5 | Import | GPX import + dedupe; Huawei screenshot extraction with review screen |
| M6 | Load analytics | TRIMP, weekly/chronic load, recovery trends, charts |
| M7 | Plan engine | Plan model, rule-based tuning, weekly review |
| M8 | Navigation | GPX course follow, OSRM routing, off-route, offline tiles |
| M9 | Polish | Optional LLM coach, FIT export |

Recording and the data repo come first because analytics and plan tuning depend on having data.

## 6. Risks

- **Old phone**: weak GPS, aged battery, OEM battery killers stopping the foreground service -> battery-optimization exemption guidance and ride recovery.
- **Screenshot OCR accuracy** varies by Huawei app version/language -> mandatory review step, keep originals.
- **Health data in git**: history is permanent; a leaked token or public repo is serious -> private repo, scoped token, no data in the app repo.
- **OSRM profile / tiles**: bike routing needs a bicycle profile; offline tiles need a permitted source.

## 7. Decisions (2026-11-03)

1. **Screenshot extraction: on-device only first** (ML Kit, no images leave the phone). Cloud vision is a possible later opt-in.
2. **Android > 10** -> min SDK 26 stays; target 34.
3. **Sensors: Huawei Watch Fit 4 only**, no separate strap/cadence/power in v1. Open item for M3: check whether the Watch Fit 4 can broadcast heart rate over standard BLE HR (0x180D) during a workout. If not, heart rate arrives after the ride via Huawei Health screenshots/GPX. Speed is phone-GPS only.
4. **Data repo:** `cyclocomp-data`, private, created when M4 starts.
5. **Plan goal:** general fitness + distance improvement + weight loss. The plan engine therefore tracks weekly distance progression, time-in-zone, body-weight trend and a calorie estimate, with a capped weekly ramp so distance grows gradually.
6. Maps: online OSM tiles in v1, offline later.
7. App name: "cyclocomp" (package `com.aliven.cyclocomp`).

M1 note: ride points are journaled to a file during recording (crash-safe) instead of Room; Room arrives with history/health records in M2+.
