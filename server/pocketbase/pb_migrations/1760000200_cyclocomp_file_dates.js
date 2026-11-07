/// <reference path="../pb_data/types.d.ts" />

// Adds the date a file record was created, which the clean-up of deleted files (pb_hooks/purge.js)
// needs to tell how long ago a file was deleted. PocketBase does not add such a field by itself.
//
// Records that already exist get today's date: without it they would count as ancient and the first
// clean-up would remove everything the app had deleted so far, with no grace period at all.
migrate((app) => {
  const files = app.findCollectionByNameOrId("files")
  if (files.fields.getByName("created")) return // already there (e.g. migration run twice)

  files.fields.add(new AutodateField({ name: "created", onCreate: true, onUpdate: false }))
  app.save(files)

  const now = new Date().toISOString().replace("T", " ")
  app.db()
    .newQuery("UPDATE files SET created = {:now} WHERE created = '' OR created IS NULL")
    .bind({ now: now })
    .execute()
}, (app) => {
  const files = app.findCollectionByNameOrId("files")
  files.fields.removeByName("created")
  app.save(files)
})
