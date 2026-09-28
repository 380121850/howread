# android/signing - local signing & channel config (NOT committed)

Sensitive signing material + channel build ids, shared by all build servers
through the NFS working tree but **never committed** (same policy as
`harmony/signing`; see the whitelist block in the root `.gitignore`).

Contents (gitignored):
- `howread.keystore` - release keystore (alias `howread`)
- `debug.keystore` - shared debug keystore (copy of the original build server's
  `~/.android/debug.keystore` so debug APKs stay installable across machines;
  standard `android` / `androiddebugkey` credentials)
- `signing.properties` - `RELEASE_*` credentials + `hw_*` Huawei ad/IAP ids

Precedence: command-line `-P` > `~/.gradle/gradle.properties` > this file.
The app module `build.gradle` loads this file automatically; a fresh clone
without this directory still configures - release falls back to the placeholder
(fails only when actually signing, as before) and debug falls back to the
per-machine default keystore.

Red line: before a store-release build, replace the `hw_*Id` TEST slots with
real AGC media-slot ids or delete those keys (restore dormant ads).
