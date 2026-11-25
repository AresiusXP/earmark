# Audiobooks: Android app

Kotlin + Jetpack Compose, single module `:app`, package `xyz.aresius.audiobooks`. Design: `../docs/DESIGN.md` ("App direction") and
`DESIGN.md` (visual design). Server contract: `../api/openapi.yaml` (binding; `ContractTest` fails when a path of the contract has no
method in `AudiobooksApi`). This is the Phase 2 skeleton: no audio player and no download or torrent engine yet.

## Build and test

```bash
source ~/android/env.sh                 # JDK 17 + Android SDK (user-local)
cd app
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Toolchain: Gradle 9.9.1 (wrapper), AGP 9.5.1 (built-in Kotlin), Kotlin 2.4.21 + Compose compiler plugin (all warnings are errors), KSP,
Hilt, Room, Retrofit + kotlinx-serialization, Coil 3, Palette, Tink. compileSdk 37 (install `platforms;android-37.0`), targetSdk 35,
minSdk 26. Versions live in `gradle/libs.versions.toml`. Strings: edit the table in `scripts/gen_strings.py` and run it (it writes English and
Dutch together, so no key can miss a translation; CI checks they are in sync).

`versionName` is `-PappVersion=X.Y.Z` or, by default, the repo version (`../.release-please-manifest.json`); `versionCode = X*10000 + Y*100 + Z`.

## Release signing

`assembleRelease` reads `AUDIOBOOKS_KEYSTORE_FILE`, `AUDIOBOOKS_KEYSTORE_PASSWORD`, `AUDIOBOOKS_KEY_ALIAS`, `AUDIOBOOKS_KEY_PASSWORD` from the
environment or Gradle properties; without them the release APK is signed with the debug key (local use only). The key of the maintainer
lives outside the repo in `~/android/keys/audiobooks.jks` with the settings in `~/android/keys/audiobooks.properties` (mode 600):

```bash
set -a; source ~/android/keys/audiobooks.properties; set +a
./gradlew :app:assembleRelease          # app/build/outputs/apk/release/app-release.apk
```

**Back up `audiobooks.jks` and `audiobooks.properties`** (password manager, encrypted drive). If they are lost, a future APK cannot update an
installed app: it would have to be uninstalled first (losing local data). Never commit them (`*.jks`, `*.keystore` are git-ignored).
Debug and release builds use different keys: uninstall one before installing the other.

## Architecture

```
core/network  AudiobooksApi (Retrofit, one method per path of the contract), Dtos (hand-written from openapi.yaml), ApiResult/ApiError
              (typed {"error":{"code","message"}}), HttpClientFactory: AuthInterceptor (token for the signed-in host only),
              RetryInterceptor (502/503/504 + Retry-After; IO errors for idempotent methods), TokenAuthenticator (401 -> refresh once ->
              sign out when the refresh is rejected), no redirects (the download route answers 302 and the caller reads Location)
core/auth     Session + SessionStore (Tink AES-GCM, Android Keystore master key, DataStore blob), AuthRepository
              (bootstrap / join / link / sign out; sign-out never depends on the server), GoogleAuthRepository (Sign in with Google: sign in,
              join, link, unlink), GoogleTokenSource (Credential Manager; a debug fake for the demo backend), CodeFormat (invite/link
              code normalisation), InputValidation (https required; plain http only for localhost/127.0.0.1/10.0.2.2,
              or any host in debug builds; also enforced by the network security config)
core/qr       QrCode: small pure-Kotlin QR encoder (byte mode, level M, versions 1-10; QrCodeTest decodes it with ZXing)
core/data     Room AudiobooksDb (cache_entry, library_entry scoped by user), Catalog/Library/Me/Debrid/Admin/Release repositories,
              AppPreferences (DataStore), SessionCleanup (wipes everything of an account when its session ends)
core/update   Update check (GET /v1/app/update), APK download with size + SHA-256 check, PackageInstaller session, banner state machine
core/design   "Reading room" theme: Color, Type (Fraunces + Hanken Grotesk), Shape, Motion, WavyProgressBar, CoverColors (Palette), components
feature/*     one package per screen: XxxScreen + XxxViewModel (Hilt, StateFlow, Load<T> = loading / content / failed with retry)
              onboarding, discover, search, book, library, settings, admin (Users), debrid + bittorrent (shared by onboarding and Settings), update
di/           Hilt modules; src/debug and src/release each provide the (debug-only) interceptors
app/          Routes (typed), AppRoot (onboarding gate / shell), BottomBar, NavHost (shared-element transition cover -> Book detail)
```

Slots for later phases: `core/player` (Media3 service), `core/download` (WorkManager + engines) next to `core/data`; `ReleaseRepository`
already has `prepare`, `downloadLink` (302/202) and `reportTorrentFiles`.

## Flows

* **Onboarding** (`feature/onboarding`): server URL (default `https://audiobooks.aresius.xyz`, changeable) -> "I have an invite code" / "First
  setup (instance password)" / "Link this device (link code)" -> display name + device name -> Debrid step (TorBox / Real-Debrid key,
  validated live by `PUT /v1/me/debrid/{provider}`, skippable) -> BitTorrent step (risk explained, switch "Allow BitTorrent downloads"
  default OFF; turning it on needs a confirmation; sets `p2p_enabled` and a coherent `source_mode` through `PATCH /v1/me`). A kill
  during the last two steps resumes there.
  **Continue with Google** shows first when `GET /v1/version` says `google_enabled` (hidden otherwise; explained when the device has no
  Google Play services). Invite and link codes are 26 base32 characters: the field accepts pasted text (case, spaces, dashes forgiven,
  counted live) and **Scan QR code** (Google code scanner, no camera permission; paste is the fallback).
* **Main**: Discover, Search, Library, Settings (bottom bar); Book detail (cover, people, series, runtime, description, ranked releases with
  format/size/seeders, cached badges per provider and "usable via" chips: Debrid / BitTorrent / needs a key). Play and Download are
  present and answer "Coming in a later phase".
* **Settings**: account name, region, languages, preferred format, source mode, wallpaper colors (opt-in, Android 12+), debrid keys
  (add/replace/check/remove with status, the key is never shown again), BitTorrent switch with warning, devices (revoke, "Link another
  device": QR code + code in blocks of five + Copy/Share + countdown), Google account (link, shows the email, unlink with confirmation), version + update check, sign out (revokes this device on the server first). Admins also see **Users and invites**
  (users with disable/enable; invites: the code is shown once as QR + Copy/Share, revoke).

## Demo backend (debug builds only)

`src/debug` contains `FakeBackend`, an in-process OkHttp interceptor that answers every route of the contract for the host
`demo.audiobooks.test` (catalog with 24 public-domain classics and generated covers, releases, library, debrid, admin, devices, update).
It exists only in debug builds (`BuildConfig.DEBUG_TOOLS`; the release source set provides nothing), so the screens run on an emulator
before the server implements catalog, releases and library.

1. `./gradlew :app:installDebug`
2. On the first screen tap **Use the demo backend (debug build)** (or type `http://demo.audiobooks.test`) and continue.
3. Sign in with: **First setup** + instance password `demo-password` (you become admin), or **invite code**
   `DEMO-DEMO-DEMO-DEMO-DEMO-DEMO-DE` (a normal user). Codes made in Settings and Users are 26 characters like the real ones and work in the demo.
   **Sign in with Google** works too, without Google: the demo backend reports `google_enabled`, and a debug-only fake supplies the ID token
   of "Demo <demo.listener@example.test>" (the demo server's nonces are routed to the fake; any other server uses the real Credential
   Manager). Try: Continue with Google -> "not linked" -> invite code -> joined; or First setup, then Settings > Link Google account. Debrid key `badkeybadkey` is rejected (`bad_debrid_key`), any other key of 8+
   characters is accepted. A search for `error` returns a 503 to show the error state. State lives in memory: it resets when the app
   process dies (a stored session then continues as the demo admin).

Against a real server in development use `http://10.0.2.2:<port>` (the emulator's host) or, in debug builds, any LAN address.

## Tests

`./gradlew :app:testDebugUnitTest`: JUnit + MockWebServer + Turbine. API client (error parsing, retry, Retry-After, refresh, sign-out,
token never sent to other hosts), the contract coverage test, DTO parsing, auth, repositories, session cleanup, the updater, WCAG contrast
of both palettes, and the ViewModels (onboarding, debrid, BitTorrent, settings, Google account, admin, discover, search, book, library), the Google flow state
machine (fresh nonce + fresh ID token after every failed attempt, not linked -> join, link/unlink, 409), the QR encoder, code
normalisation. The app uses no regular expressions (plain string code), so there is no `AppRegexIcuTest` (add one, run on an emulator, with
the first regex).

## Sign in with Google

Credential Manager (`androidx.credentials` + `googleid`); `GetSignInWithGoogleOption` for the button (falls back to `GetGoogleIdOption`
with `filterByAuthorizedAccounts(false)` and no auto-select). The WEB client ID is `BuildConfig.GOOGLE_SERVER_CLIENT_ID`
(default in `app/build.gradle.kts`, override with `-PgoogleServerClientId=...`); the Android OAuth clients (package + debug/release signing
SHA-1) are registered in Google Cloud, nothing in code. Never log or store an ID token. Real sign-in needs a Google account on the device:
emulators and phones without one show "no Google account" (card stays, codes work).
