# Cyclocomp account server (PocketBase)

A small self-hosted server so friends can use the app with **their own account** (email + password)
and see **only their own data**. Nothing secret is built into the app: each person gets a personal
session after signing in, and the server enforces who may read what.

This is optional. The app can also sync straight to a private GitHub repository (the mode used
by the owner's own phones); the two modes can be used side by side.

## What you need

- A machine with Docker and Docker Compose (the homelab is fine).
- A way to reach it **over HTTPS from the internet**. Android blocks plain `http://`, and friends are
  not on your private network. A Cloudflare Tunnel is the easiest (no router port needs to be opened).
- Disk space: a ride is about 1 MB of GPX; a screenshot a few hundred KB if people choose to upload it.

## 1. Start the server

```
cd server/pocketbase
docker compose up -d --build
docker compose ps          # pocketbase should become "healthy"
```

The build downloads PocketBase **v0.40.6** and checks its SHA-256 against the value pinned in the
`Dockerfile`; it fails if they differ. The server listens on port **8090** of this machine's network
address (plain HTTP), so a tunnel or proxy on another machine can reach it. Do not forward that port on
the router. To keep it local-only, set `PB_BIND=127.0.0.1` in `.env`; to use another port, set `PB_PORT`.

The first start runs `pb_migrations/1760000000_cyclocomp_files.js`, which creates the `files`
collection with the access rules, turns **public sign-up off** and enables request rate limits.

## 2. Create your admin (superuser) account

```
docker compose exec pocketbase /pb/pocketbase superuser upsert you@example.com 'a-long-unique-password'
```

Use a long, unique password and keep it to yourself. The admin dashboard is at `/_/` on the server.
Anyone who knows this password can read every account's data.

## 3. Put it on the internet with HTTPS (Cloudflare Tunnel)

**If your tunnel runs somewhere else** (another container, the Proxmox host, another machine): point
its public hostname at `http://<this-machine's-IP>:8090` and you are done; skip the rest of this section.
Check from that machine first: `curl http://<this-machine's-IP>:8090/api/health`.

**If you want the tunnel bundled here:**

1. Cloudflare Zero Trust dashboard > Networks > Tunnels > create a tunnel (type "Cloudflared").
2. Add a **public hostname**, for example `fit.your-domain.org`, with service `http://pocketbase:8090`.
3. Copy the tunnel token into `.env` (copy `.env.example`; `.env` is git-ignored):
   ```
   TUNNEL_TOKEN=...
   ```
4. Start it: `docker compose --profile tunnel up -d`.
5. Open `https://fit.your-domain.org/api/health` — it should answer `{"code":200,...}`.

Then, **only if nothing but the tunnel can reach the server** (with the default `PB_BIND=0.0.0.0` other
machines on your network can too, and could fake the header, so prefer `PB_BIND=127.0.0.1` with a tunnel
on the same machine for this), open the dashboard >
Settings > Application and add `CF-Connecting-IP` under "Trusted proxy headers". Without it every
request looks like it comes from the tunnel, so the rate limits are shared by everyone.

## 4. Check the access rules (do this before inviting anyone)

```
PB_URL=https://fit.your-domain.org \
PB_SUPERUSER_EMAIL=you@example.com PB_SUPERUSER_PASSWORD='...' \
./smoke-test.sh
```

It creates two throw-away accounts and checks that one can never see, change, delete or download
the other's files, that strangers get nothing and that open sign-up is refused, then deletes both
accounts. **Every line must say `ok`.** If any line says `FAIL`, do not invite friends; send me the
output.

## 5. Add friends

Accounts are invite-only. In the dashboard open the `users` collection > **New record**, enter
their email and a password, and send it to them privately (they can change it in the app).
Tell them plainly that, as the server owner, you could technically read their data.

## 6. Forgot-password emails (optional)

The app has a **Forgot password?** button on the sign-in screen. It asks the server to email a one-time
link to a small page of its own (`/reset.html`, served from `pb_public`), where the person chooses a new
password. It needs the server to be able to send email:

1. Update the server (`git pull`, `docker compose up -d --build`): the migration
   `1760000100_cyclocomp_password_reset.js` puts the link in the email template and rate-limits reset requests
   (5 per 10 minutes).
2. Dashboard > Settings > **Application**: set **Application URL** to your public HTTPS address, e.g.
   `https://fit.your-domain.org` (the link in the email is built from it).
3. Dashboard > Settings > **Mail settings**: set the sender name and address, enable **Use SMTP mail server**
   and fill in your provider's host, port, username and password. Use **Send test email** to check it.
   Any SMTP service works (a Gmail app password, Brevo, Resend, your own mail server).
4. Try it: on a phone, enter an account's email on the sign-in screen and tap **Forgot password?**.

The app always answers "if this email has an account, a link was sent" so nobody can use it to find out
which emails have accounts. Without SMTP the app says the email could not be sent; you can still reset
a password yourself in the dashboard (users > the account > password).

## 7. Files the app deleted

When a ride or route is deleted in the app, the app uploads a small marker named `<path>.deleted` so
the other phone deletes its copy too. The original file and the marker would stay on the server
forever, so a daily job (`pb_hooks/cleanup.pb.js`, 03:17 server time) clears them out:

- the original file is removed **30 days** after its marker was created;
- the marker is removed **90 days** after it was created. It has to outlive the time a phone can stay
  offline: a phone that never saw the marker would upload the deleted ride again.

Only `rides/` and `routes/` paths with a marker are touched. The counts are `ORIGINAL_DAYS` and
`MARKER_DAYS` in `pb_hooks/purge.js`. The migration `1760000200_cyclocomp_file_dates.js` adds the
creation date this needs; files that already exist start their clock on the day of the migration, so
nothing that was deleted earlier disappears at once.

A superuser can also run it by hand, e.g. `POST /api/cyclocomp/purge?originalDays=30&markerDays=90`
(optional `&owner=<user id>`); the answer says how many files and markers were removed. The smoke test
uses this on its throw-away account.

## Looking after it

- **Backups:** the data is the `pb_data` volume (SQLite database + uploaded files). Dashboard >
  Settings > Backups can make scheduled backups (keep a copy off this machine), or stop the container
  and copy the volume. Test a restore once.
- **Updates:** PocketBase announced that v0.40.6 is most likely its last release published on
  GitHub; later releases come from a different host. To update, change `PB_VERSION` and the checksums
  in the `Dockerfile` from the official release, rebuild, and run the smoke test again.
- **Disk:** watch free space; there is no per-account quota.
- **Deleting someone's data:** delete their record in the `users` collection; their files go with it.
  In the app a person can delete their own account the same way.
- **Admin password lost or leaked:** `docker compose exec pocketbase /pb/pocketbase superuser upsert ...`
  sets a new one; rotate it if there is any doubt.

## Verification

Written against the PocketBase v0.40 sources and API types. The smoke test passed **23 of 23 checks**
on PocketBase v0.40.6 running in Docker behind a Cloudflare Tunnel (run through the public HTTPS
address, so uploads, file tokens and auth headers were checked end to end). After the clean-up of
deleted files was added it passed **33 of 33** on a local Docker instance of v0.40.6, including data
that existed before the date migration. Run it again after every PocketBase update or change to the
migrations or hooks.
