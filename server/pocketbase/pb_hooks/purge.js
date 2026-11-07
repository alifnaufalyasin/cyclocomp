// Clears out files the app has deleted. Shared by the daily job and the manual route
// (see cleanup.pb.js).
//
// When the app deletes a ride or route it uploads a marker named "<path>.deleted", so the other phone
// deletes its copy too. The original file and the marker would otherwise stay on the server forever:
//   - the original file is removed once its marker is older than ORIGINAL_DAYS (the big part);
//   - the marker itself is removed once it is older than MARKER_DAYS. It has to outlive the phones'
//     offline time: a phone that missed it would upload the deleted ride again.
// Only paths under rides/ and routes/ are touched. Settings and everything else are left alone.

const SUFFIX = ".deleted"
const PREFIXES = ["rides/", "routes/"]
const DAY_MS = 24 * 60 * 60 * 1000
const PAGE = 200

const ORIGINAL_DAYS = 30
const MARKER_DAYS = 90

// PocketBase stores dates as "2026-11-07 12:00:00.000Z".
function cutoff(nowMs, days) {
  return new Date(nowMs - days * DAY_MS).toISOString().replace("T", " ")
}

function isMarkerPath(path) {
  return path.length > SUFFIX.length &&
    path.endsWith(SUFFIX) &&
    PREFIXES.some((p) => path.startsWith(p))
}

// All markers created before [cutoffText], optionally of one account only.
function oldMarkers(app, cutoffText, owner) {
  let filter = "path ~ {:suffix} && created < {:cutoff}"
  const params = { suffix: SUFFIX, cutoff: cutoffText }
  if (owner) {
    filter += " && owner = {:owner}"
    params.owner = owner
  }
  const found = []
  for (let offset = 0; ; offset += PAGE) {
    const page = app.findRecordsByFilter("files", filter, "created", PAGE, offset, params)
    for (const r of page) {
      if (r && isMarkerPath(r.getString("path"))) found.push(r)
    }
    if (page.length < PAGE) break
  }
  return found
}

// Returns how many original files and markers were removed.
function run(app, opts) {
  const now = opts.now || Date.now()
  const originalDays = opts.originalDays
  const markerDays = Math.max(opts.markerDays, originalDays) // a marker never goes before its file
  let originals = 0
  let markers = 0

  for (const m of oldMarkers(app, cutoff(now, originalDays), opts.owner)) {
    const path = m.getString("path")
    const found = app.findRecordsByFilter(
      "files", "owner = {:owner} && path = {:path}", "", 1, 0,
      { owner: m.getString("owner"), path: path.slice(0, -SUFFIX.length) },
    )
    if (found.length > 0 && found[0]) {
      app.delete(found[0])
      originals++
    }
  }

  for (const m of oldMarkers(app, cutoff(now, markerDays), opts.owner)) {
    app.delete(m)
    markers++
  }
  return { originals: originals, markers: markers }
}

// Reads a number of days from text such as a query parameter; empty means [fallback].
function parseDays(text, fallback) {
  if (text === undefined || text === null || text === "") return fallback
  if (!/^[0-9]{1,4}$/.test(text)) throw new Error("days must be a whole number from 0 to 9999")
  return parseInt(text, 10)
}

module.exports = { run, cutoff, isMarkerPath, parseDays, ORIGINAL_DAYS, MARKER_DAYS, SUFFIX }
