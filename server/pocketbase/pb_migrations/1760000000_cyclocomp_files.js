/// <reference path="../pb_data/types.d.ts" />

// Creates what the Cyclocomp app needs and locks it down:
//  - the `files` collection: one record per stored file, owned by exactly one account;
//  - access rules so an account can only ever see and change its own files;
//  - invite-only accounts (strangers cannot sign up);
//  - request rate limits.
migrate((app) => {
  const users = app.findCollectionByNameOrId("users")

  // Only the superuser creates accounts. Public sign-up on the internet would let anyone fill the disk.
  users.createRule = null
  app.save(users)

  const files = new Collection({
    type: "base",
    name: "files",
    // Every rule is tied to the signed-in account. A request for somebody else's record behaves as
    // if it did not exist.
    listRule: "owner = @request.auth.id",
    viewRule: "owner = @request.auth.id",
    createRule: "@request.auth.id != '' && @request.body.owner = @request.auth.id",
    updateRule: "owner = @request.auth.id && @request.body.owner:isset = false",
    deleteRule: "owner = @request.auth.id",
    fields: [
      // Deleting an account deletes all of its files with it.
      { name: "owner", type: "relation", required: true, collectionId: users.id, cascadeDelete: true, maxSelect: 1 },
      // Path inside the account, e.g. rides/2026/20261107_060200.gpx
      { name: "path", type: "text", required: true, min: 1, max: 300 },
      // Git blob sha of the content, so the app can tell whether its copy is up to date.
      { name: "sha", type: "text", max: 64 },
      // protected: downloading needs a short-lived file token of the owner.
      { name: "content", type: "file", maxSelect: 1, maxSize: 26214400, protected: true },
    ],
    indexes: [
      "CREATE UNIQUE INDEX `idx_files_owner_path` ON `files` (`owner`, `path`)",
    ],
  })
  app.save(files)

  const settings = app.settings()
  settings.rateLimits.enabled = true
  settings.rateLimits.rules = [
    // Sign-in attempts: slows down password guessing.
    { label: "*:auth", audience: "", duration: 60, maxRequests: 10 },
    // Everything else.
    { label: "/api/", audience: "", duration: 10, maxRequests: 200 },
  ]
  app.save(settings)
}, (app) => {
  app.delete(app.findCollectionByNameOrId("files"))
})
