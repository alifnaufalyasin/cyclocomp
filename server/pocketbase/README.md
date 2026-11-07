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
`Dockerfile`; it fails if they differ. The server listens on `127.0.0.1:8090` only.

The first start runs `pb_migrations/1760000000_cyclocomp_files.js`, which creates the `files`
collection with the access rules, turns **public sign-up off** and enables request rate limits.

## 2. Create your admin (superuser) account

```
docker compose exec pocketbase /pb/pocketbase superuser upsert you@example.com 'a-long-unique-password'
```

Use a long, unique password and keep it to yourself. The admin dashboard is at `/_/` on the server.
Anyone who knows this password can read every account's data.

## 3. Put it on the internet with HTTPS (Cloudflare Tunnel)

1. Cloudflare Zero Trust dashboard > Networks > Tunnels > create a tunnel (type "Cloudflared").
2. Add a **public hostname**, for example `fit.your-domain.org`, with service `http://pocketbase:8090`.
3. Copy the tunnel token into `.env` (copy `.env.example`; `.env` is git-ignored):
   ```
   TUNNEL_TOKEN=...
   ```
4. Start it: `docker compose --profile tunnel up -d`.
5. Open `https://fit.your-domain.org/api/health` — it should answer `{"code":200,...}`.

Then, **only if the server can be reached solely through the tunnel**, open the dashboard >
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

## Not verified yet

These files were written against the PocketBase v0.40 sources and API types, but have not been run
here (there is no PocketBase in the development sandbox). The smoke test is the proof: please run it
and tell me the result.
