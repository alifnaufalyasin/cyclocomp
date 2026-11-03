# Cyclocomp

Turn an old Android phone into a bike computer, with health-data import and plan tuning.
See [PLAN.md](PLAN.md) for the full plan and milestones.

**Status: M1** — GPS ride recording (foreground service), live speed/distance/time/ascent,
auto-pause, crash-safe journal, GPX export via share sheet.

Build: `gradle assembleDebug` (JDK 17, Android SDK 34). CI builds a debug APK on every push.
