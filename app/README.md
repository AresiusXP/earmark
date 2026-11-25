# Audiobooks: Android app

Kotlin + Jetpack Compose, single module `:app`, package `xyz.aresius.audiobooks`. Design: `../docs/DESIGN.md` ("App direction") and
`DESIGN.md` (visual design). Server contract: `../api/openapi.yaml` (binding; `ContractTest` fails when a path of the contract has no
method in `AudiobooksApi`). Phase 2 skeleton, Phase 3b offline downloads (`core/download`), Phase 4 BitTorrent engine (`core/torrent`); the audio player (`core/player`) comes from its own branch.

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

```
core/download  offline downloads (Phase 3b), see below; feature/download = its UI
core/torrent   TorrentEngine interface (BitTorrent, Phase 4); core/download plugs into it through TorrentSource
```

## Downloads (core/download, feature/download)

Design: with a debrid key the player STREAMS by default; "Download" is for offline use (per book, optionally "auto-download what I'm
listening to" on Wi-Fi). Books obtained through BitTorrent are always downloaded. Files live in app-private storage
(`filesDir/books/<asin>/<releaseHash>/<server path>`, no storage permission); only the files of THIS book are fetched (the release's
`files` / `torrent_files`, matched with the provider's list by PATH, in the server's chapter order).

```
DownloadsRepository   what the UI and the player use: state(asin): Flow<BookDownloadState> (None, Queued, Preparing(%), Downloading(bytes,
                      speed, ETA), Paused(Wi-Fi / network / VPN / battery), Failed(reason, retry-after), Completed), downloads, downloadedAsins,
                      storageInfo, enqueue / retry / delete / deleteAll, autoDownloadIfEnabled(book, release) for the player, wipe
LocalFiles            (the player's contract) DownloadedLocalFiles: localFile(asin, hash, path) = ONLY a complete file of the signed-in user
                      (checked on disk), hasDownloads(asin)
DownloadsDb (Room)    download_book (one job per book: release, source, state, failure) + download_file (path, localPath, size, state), all
                      scoped by userId; own database file (downloads.db), not AudiobooksDb
BookDownloadWorker    one unique expedited WorkManager job per book (dataSync foreground notification, network constraint UNMETERED or
                      CONNECTED from the "mobile data" setting, exponential backoff 30 s, reschedules itself after the Android 15 dataSync
                      timeout); MaintenanceWorker every 12 h; WorkManager restores jobs after a reboot, DownloadsStarter re-enqueues active jobs
DownloadRunner        the policy and the result of a run (Done / Retry / Waiting / Failed / Gone); plain Kotlin, tested with fakes
DownloadSource        DebridSource (prepare + poll with retry_after_s; per file GET /v1/releases/{hash}/files/{index}/download -> 302 -> CDN,
                      fresh link right before each file, never stored, the CDN call carries no Authorization) and TorrentSource (TorrentEngine
                      when a binding exists, else FailReason.TorrentUnavailable)
HttpFileFetcher       one file: `.part` + HTTP Range resume + size check + atomic rename; only 200/206 with an audio-ish content type is written
StorageManager        usage, storage limit (default 5 GB) with LRU eviction of FINISHED books only (never the one playing, never active jobs),
                      auto-delete N days after finishing (off), orphan cleanup, repair of vanished files, wipe
PathSafety            every server-provided path is untrusted: absolute paths, `..`, backslashes, control characters, drive letters are rejected
```

* Wi-Fi only is the default (setting "Also download on mobile data"); auto-download is always Wi-Fi only. Free space (300 MB reserve) and the
  limit are checked before a job starts and again when the file list is known.
* Hooks other features implement/override: `PlayingBook` (core/download; the player binds it, default none), `TorrentEngine` (core/torrent; optional
  binding), `SessionWipeHook` (core/data; sign-out/user switch wipes the downloads through it).
* UI: Book detail (`DownloadButton`, `DownloadStatus`, `ReleaseDownloadButton`: the player can reuse them), Downloads screen (Library toolbar and
  Settings), Settings > Downloads (`DownloadSettingsSection`), Library "Downloaded" badge/filter that also works offline.
core/torrent TorrentEngine (contract) + LibTorrentEngine (libtorrent4j): metadata fetch, selective download, TorrentPolicy (opt-in, VPN, Wi-Fi,
              battery, seeding), TorrentSettings (DataStore), AndroidDeviceStateMonitor, TorrentService (foreground, dataSync) + resume worker,
              TorrentPaths (zip-slip), TorrentStore (resume data in the no-backup dir). Binding: di/TorrentModule. Details: DESIGN.md "BitTorrent engine".

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
  format/size/seeders, cached badges per provider and "usable via" chips: Debrid / BitTorrent / needs a key, a Download button with its
  state per book and per release). Play still answers "Coming in a later phase".
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
3. Sign in with: **First setup** + instance password `demo-password` (you become admin), or **invite code** `DEMO-INVITE` (a normal user).
   Link codes made in Settings work in the demo too. Add any key of 8+ characters in Settings first: then Download works against the demo
   (prepare needs three polls, the files are 4 MB silent WAVs served under `/cdn/` with Range support, see `DemoDownloads`). Debrid key `badkeybadkey` is rejected (`bad_debrid_key`), any other key of 8+
   characters is accepted. A search for `error` returns a 503 to show the error state. State lives in memory: it resets when the app
   process dies (a stored session then continues as the demo admin).

Against a real server in development use `http://10.0.2.2:<port>` (the emulator's host) or, in debug builds, any LAN address.

## BitTorrent engine (debug harness, tests)

* Debug screen: `adb -s emulator-5600 shell am start -n xyz.aresius.audiobooks/.debug.TorrentDebugActivity` (debug builds only, `src/debug`): paste a
  magnet (public domain only), list files, tick files (tick order = download order), download, pause (= cancel the collector), remove. It shows
  the policy inputs and the engine activity; the foreground service notification appears while a download is collected.
* `./gradlew :app:connectedDebugAndroidTest` (needs an emulator; `export ANDROID_SERIAL=emulator-<port>` so a physical device is never used):
  `LibTorrentEngineTest` generates a torrent at test time and seeds it from a second libtorrent session on 127.0.0.1 (no trackers, no DHT, no
  real swarm): metadata fetch, selective download (unselected files are never created), VPN drop / return, cancel + resume from disk, seeding
  policy, malicious paths, production session settings. `TorrentServiceTest` covers the foreground service and, with
  `-Pandroid.testInstrumentationRunnerArguments.fgsTimeout=true` after `adb shell device_config put activity_manager data_sync_fgs_timeout_duration 20000`,
  the Android 15 dataSync timeout. `RealSwarmSmokeTest` is a manual, off-by-default test against a real LibriVox magnet
  (`-Pandroid.testInstrumentationRunnerArguments.librivoxMagnet=...`; your IP is visible in a real swarm: run it behind a VPN).
* Release ships `arm64-v8a` only; debug adds `x86_64` for the emulator (`ndk.abiFilters` per build type in `app/build.gradle.kts`).

## Tests

`./gradlew :app:testDebugUnitTest`: JUnit + MockWebServer + Turbine (+ Robolectric for Room/WorkManager). API client (error parsing, retry,
Retry-After, refresh, sign-out, token never sent to other hosts), the contract coverage test, DTO parsing, auth, repositories, session cleanup,
the updater, WCAG contrast of both palettes, the ViewModels (onboarding, debrid, BitTorrent, settings, Google account, admin, discover, search,
book, library), the Google flow state machine (fresh nonce + fresh ID token after every failed attempt, not linked -> join, link/unlink, 409),
the QR encoder, code normalisation. Downloads: `core/download/*Test` (path safety, Range resume / atomic rename / 202 and JSON never saved /
redirect without Authorization / error mapping, Wi-Fi gating with a fake network, LRU eviction, orphans, per-user isolation, LocalFiles,
TorrentSource with a fake engine, worker retry/backoff). BitTorrent: policy / path / planner / settings tests (`core/torrent`). The app uses no
regular expressions, so there is no `AppRegexIcuTest` (add one, run on an emulator, with the first regex).

## Sign in with Google

Credential Manager (`androidx.credentials` + `googleid`); `GetSignInWithGoogleOption` for the button (falls back to `GetGoogleIdOption`
with `filterByAuthorizedAccounts(false)` and no auto-select). The WEB client ID is `BuildConfig.GOOGLE_SERVER_CLIENT_ID`
(default in `app/build.gradle.kts`, override with `-PgoogleServerClientId=...`); the Android OAuth clients (package + debug/release signing
SHA-1) are registered in Google Cloud, nothing in code. Never log or store an ID token. Real sign-in needs a Google account on the device:
emulators and phones without one show "no Google account" (card stays, codes work).
