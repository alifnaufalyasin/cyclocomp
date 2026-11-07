/// <reference path="../pb_data/types.d.ts" />

// Removes files the app has deleted (rules and day counts: purge.js).
// Handlers run in their own context, so the shared code is required inside each handler.

// Every day at 03:17 server time.
cronAdd("cyclocomp_purge", "17 3 * * *", () => {
  const purge = require(`${__hooks}/purge.js`)
  const r = purge.run($app, { originalDays: purge.ORIGINAL_DAYS, markerDays: purge.MARKER_DAYS })
  $app.logger().info("cyclocomp purge", "originals", r.originals, "markers", r.markers)
})

// Run it by hand (superuser only), e.g. to check the behaviour with small day counts:
//   POST /api/cyclocomp/purge?originalDays=30&markerDays=90[&owner=<user id>]
// "owner" limits it to one account, which is what the smoke test uses.
routerAdd("POST", "/api/cyclocomp/purge", (e) => {
  const purge = require(`${__hooks}/purge.js`)
  const q = e.request.url.query()
  let opts
  try {
    opts = {
      originalDays: purge.parseDays(q.get("originalDays"), purge.ORIGINAL_DAYS),
      markerDays: purge.parseDays(q.get("markerDays"), purge.MARKER_DAYS),
      owner: q.get("owner") || "",
    }
  } catch (err) {
    throw new BadRequestError(String(err.message || err))
  }
  return e.json(200, purge.run($app, opts))
}, $apis.requireSuperuserAuth())
