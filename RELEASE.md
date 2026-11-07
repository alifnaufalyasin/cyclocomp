# Releasing and updating the app

The app can update itself: **Sync > App update** looks for the newest GitHub Release of this
repository, downloads its APK and asks Android to install it over the old one.

Android only installs an update that is **signed with the same key** as the installed app, so
releases are signed with one fixed key that lives only in GitHub secrets and in your own backup.
The ordinary CI build (`Build APK`) produces *debug* APKs, which are signed with a different,
throw-away key; they are for testing and cannot be updated in place by a release.

## One-time setup

1. Create a signing key (on your own computer, keep the file and passwords safe — **if you lose
   them you can never update installed apps again**):

   ```
   keytool -genkeypair -v -keystore cyclocomp-release.jks -alias cyclocomp \
     -keyalg RSA -keysize 4096 -validity 10000
   ```

2. Add four repository secrets (Settings > Secrets and variables > Actions):

   | Secret | Value |
   |---|---|
   | `RELEASE_KEYSTORE_BASE64` | `base64 -w0 cyclocomp-release.jks` |
   | `RELEASE_STORE_PASSWORD` | the keystore password |
   | `RELEASE_KEY_ALIAS` | `cyclocomp` |
   | `RELEASE_KEY_PASSWORD` | the key password |

3. Make a **read-only update token** for the phones: a fine-grained token limited to this
   repository with *Contents: Read-only* (separate from the data-repo token). Enter it in
   **Sync > App update** on each phone.

## Publishing a version

Tag the commit on `main` and push the tag:

```
git tag v0.2.0 && git push origin v0.2.0
```

The `Release APK` workflow runs the tests, builds a signed APK named `cyclocomp-0.2.0.apk`
with its `.sha256`, and publishes a GitHub Release. The version name comes from the tag and the
version code is `major*1000000 + minor*1000 + patch`, so it always increases with the version.
Tags must look like `vMAJOR.MINOR.PATCH`.

## On the phone

- **Check for update** compares the installed version with the newest release.
- **Download and install** downloads the APK, checks its size, its SHA-256 and that it is signed
  with the same key as the installed app, then opens Android's installer. The first time, Android
  asks you to allow Cyclocomp to install apps; Android always asks you to confirm each update.

### Moving from a debug build to a release build (once)

A debug APK and a release APK have different signatures, so Android refuses to update one with
the other. Do this once per phone: run **Sync now** so everything is in the data repo, uninstall
the debug build, install the first release APK, enter the repo/token again and **Sync now**.
After that, updates install in place and keep your data.
