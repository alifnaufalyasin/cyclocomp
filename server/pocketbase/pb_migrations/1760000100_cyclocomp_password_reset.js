/// <reference path="../pb_data/types.d.ts" />

// Password reset by email: the email links to reset.html (served from pb_public by this same server)
// instead of PocketBase's admin dashboard, and asking for a reset is rate limited so the server cannot
// be used to flood somebody's inbox. Sending needs SMTP settings (see README); without them the app
// simply reports that the email could not be sent.
migrate((app) => {
  const users = app.findCollectionByNameOrId("users")
  users.resetPasswordTemplate.subject = "Reset your Cyclocomp password"
  users.resetPasswordTemplate.body =
    "<p>Hello,</p>" +
    "<p>Somebody asked to choose a new password for your Cyclocomp account. Use the button below to do it.</p>" +
    "<p><a class=\"btn\" href=\"{APP_URL}/reset.html?token={TOKEN}\" target=\"_blank\" rel=\"noopener\">Choose a new password</a></p>" +
    "<p>The link works once and expires soon. If you did not ask for this, ignore this email: your password stays as it is.</p>" +
    "<p>Cyclocomp</p>"
  app.save(users)

  const settings = app.settings()
  const rules = []
  const current = settings.rateLimits.rules
  for (let i = 0; i < current.length; i++) rules.push(current[i])
  rules.push({ label: "/api/collections/users/request-password-reset", audience: "", duration: 600, maxRequests: 5 })
  settings.rateLimits.rules = rules
  app.save(settings)
}, (app) => {
  const settings = app.settings()
  const rules = []
  const current = settings.rateLimits.rules
  for (let i = 0; i < current.length; i++) {
    if (current[i].label !== "/api/collections/users/request-password-reset") rules.push(current[i])
  }
  settings.rateLimits.rules = rules
  app.save(settings)
})
