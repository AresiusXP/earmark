# Audiobooks: Android app

Kotlin + Jetpack Compose, single module `:app`, package `xyz.aresius.audiobooks`. Design: `../docs/DESIGN.md` ("App direction") and
`DESIGN.md` (visual design). Server contract: `../api/openapi.yaml` (binding; `ContractTest` fails when a path of the contract has no
method in `AudiobooksApi`). Phase 2 skeleton, Phase 3 audio player (`core/player`, `feature/player`), Phase 3b offline downloads (`core/download`), Phase 4 BitTorrent engine (`core/torrent`).

## Build and test

```bash
source ~/android/env.sh                 # JDK 17 + Android SDK (user-local)
cd app
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Toolchain: Gradle 9.9.1 (wrapper), AGP 9.5.1 (built-in Kotlin), Kotlin 2.4.21 + Compose compiler plugin (all warnings are errors), KSP,
Hilt, Room, Retrofit + kotlinx-serialization, Coil 3, Palette, Tink, Media3 (ExoPlayer + session), WorkManager. compileSdk 37 (install `platforms;android-37.0`), targetSdk 35,
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
              RetryInterceptor (502/503/504 + Retry-After; IO errors for idempotent methods), TokenAuthenticator (every request is bound to the sign-in it was made under: `SessionStore.handle()`/`SessionHandle.id` changes on sign-in, user switch and sign-out, not on a refresh; a 401 of an earlier sign-in is never retried with the current token, a refresh is stored/cleared only by `replaceIfCurrent`/`clearIfCurrent`, so a sign-out or user switch mid-refresh wins; 401 -> refresh once ->
              sign out when the refresh is rejected); `api()` and `plain()` (auth, onboarding, refresh, APK) NEVER follow redirects (a redirect is an error: a 307/308 can't carry a
              body to another host; the download route answers 302 and the caller reads Location), `cdn()` (covers, provider CDN downloads/streams) is credential-free and follows them
core/auth     Session + SessionStore (Tink AES-GCM, Android Keystore master key, DataStore blob), AuthRepository
              (bootstrap / join / link / sign out; sign-out never depends on the server), GoogleAuthRepository (Sign in with Google: sign in,
              join, link, unlink), GoogleTokenSource (Credential Manager; a debug fake for the demo backend), CodeFormat (invite/link
              code normalisation), InputValidation (https required; plain http only for localhost/127.0.0.1/10.0.2.2,
              or any host in debug builds; also enforced by the network security config)
core/qr       QrCode: small pure-Kotlin QR encoder (byte mode, level M, versions 1-10; QrCodeTest decodes it with ZXing)
core/data     Room AudiobooksDb (cache_entry, library_entry scoped by user), Catalog/Library/Me/Debrid/Admin/Release repositories,
              AppPreferences (DataStore), SessionCleanup (wipes everything of an account when its session ends)
core/update   Update check (GET /v1/app/update), APK download with size + SHA-256 check, PackageInstaller session, banner state machine
core/player   Media3 playback: PlaybackService (MediaSessionService) + PlaybackCore, BookTimeline / BookChapters (one timeline per book), BookPlayer
              (chapters as the session playlist), TrustedControllers (the exported session's gate: own uid / platform-trusted / allowlisted packages verified against their uid = full, platform-uid media keys = keys only, everyone else refused; custom commands re-checked), StreamLinkResolver + PlaybackDataSources (302 -> CDN, no token on the CDN), BookLoader +
              ReleaseChooser, ProgressSync (Room `audiobooks_player.db`, last write wins, offline queue), BookmarkRepository (client_id),
              DurationReporter (POST durations), SleepTimerLogic,
              PlayerHub (state) + PlaybackController (commands), PlayerPreferences (skip lengths, smart rewind)
core/download  offline downloads (Phase 3b), see below; feature/download = its UI
core/torrent   TorrentEngine (contract) + LibTorrentEngine (libtorrent4j, Phase 4): metadata fetch, selective download, TorrentPolicy (opt-in, VPN, Wi-Fi,
               battery, seeding), TorrentSettings (DataStore), AndroidDeviceStateMonitor, TorrentService (foreground, dataSync) + resume worker,
               TorrentPaths (zip-slip), TorrentStore (resume data in the no-backup dir). Binding: di/TorrentModule. Details: DESIGN.md "BitTorrent engine".
core/design   "Reading room" theme: Color, Type (Fraunces + Hanken Grotesk), Shape, Motion, WavyProgressBar, CoverColors (Palette), components
feature/*     one package per screen (player: PlayerScreen, MiniPlayer, sheets, Settings > Playback): XxxScreen + XxxViewModel (Hilt, StateFlow, Load<T> = loading / content / failed with retry)
              onboarding, discover, search, book, library, download (Downloads screen, DownloadButton), settings, admin (Users), debrid + bittorrent (shared by onboarding and Settings), update
di/           Hilt modules; src/debug and src/release each provide the (debug-only) interceptors
app/          Routes (typed), AppRoot (onboarding gate / shell), BottomBar, NavHost (shared-element transition cover -> Book detail)
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
* Hooks other features implement/override: `PlayingBook` (core/download; bound by the player), `TorrentEngine` (core/torrent; optional
  binding), `SessionWipeHook` (core/data; sign-out/user switch wipes the downloads through it).
* UI: Book detail (`DownloadButton`, `DownloadStatus`, `ReleaseDownloadButton`; next to Play), Downloads screen (Library toolbar and
  Settings), Settings > Downloads (`DownloadSettingsSection`), Library "Downloaded" badge/filter that also works offline.

The player consumes downloads through the `LocalFiles` interface (`core/download/LocalFiles.kt`, bound to `DownloadedLocalFiles` in
`di/LocalFilesModule`): a complete local copy always wins over streaming, file by file. The player in turn tells the storage rules which book it
holds through `PlayingBook` (`core/player/PlayerPlayingBook`, bound in `di/PlayerModule`), so the LRU eviction never deletes it, and calls `DownloadsRepository.autoDownloadIfEnabled` when a streamed book starts (`PlaybackService`).
`ReleaseRepository` has `prepare`, `downloadLink` (302/202) and `reportTorrentFiles`.

## Contract v1.1 in the app

Everything the server added in v1.1 (`api/openapi.yaml`, see its diff since `f523b7d`) is used; every new field is optional on the wire (`ignoreUnknownKeys`,
absent = null), so an older server keeps working.

* **Durations in** (`BookLoader.filesOf`): the initial length of each file of the timeline is, by PATH, what this phone measured itself
  (`file_duration`), else `duration_ms` of the book page (`files` first, `torrent_files` for the gaps; other phones reported it), else the
  estimate from the catalog runtime (`BookTimeline`). ExoPlayer's own measurement replaces any of them once the file is prepared.
* **Durations out** (`DurationReporter`, `POST /v1/releases/{hash}/durations`): only lengths ExoPlayer measured here by preparing the file and for
  which the book page had none. Best effort and never in the way of playback: a few seconds (20 s) after the first new length, and when playback
  pauses or closes, ONE request per release carries everything pending (at most 2000 entries, 1 s to 48 h; anything else is never sent). Each
  file is marked `reported` when the server answered (the server keeps the first value ever reported and says which paths it ignored: nothing to
  resend) or refused it for good (400, 404); offline, 429, 5xx and 401 keep the rows and the progress-sync worker retries when there is a network
  (also at app start). A learned duration from before this version is sent once.
* **Chapter alignment** (`ChapterMap.align`): the Audible marks (`/chapters`) and `runtime_ms` are measured on the Audible edition WITH its
  branding: `intro_ms` ("This is Audible") at the start and `outro_ms` at the end (absent = 0). A release may have cut either, so the files can have
  four lengths: `runtime`, `runtime - intro`, `runtime - outro`, `runtime - intro - outro`. The one closest to the files' total wins (a tie, and
  every case without branding data, picks the plain `runtime`, which is exactly the old rule). The marks are used when that length is within **2 %**
  of the files' total (an estimated total counts); else one chapter per file. When the winner has the intro cut, every mark moves earlier by
  `intro_ms` (a mark inside the cut intro starts at 0; one that lies wholly inside it is dropped); marks past the end are clipped. Marks are
  never stretched. Because the server's `duration_ms` makes the files' total exact from the start, the alignment is right at once for a release
  other phones already reported.
* **Bookmarks** (`BookmarkRepository`): every bookmark gets a UUID `client_id` when it is made and keeps it across retries, so a create whose answer
  was lost is simply repeated and the server returns the bookmark it has (also at the 500 limit). A row from before v1.1 gets its id the first time
  it is sent (stored before the request). The server adds a book that is not in the library yet, as `saved`; so there is no waiting for a library
  entry any more: 404 (unknown book) and 400 drop the bookmark, 409 `limit_reached`, 429 and network failures are tried again. A merge of the server's
  list adopts the server id of a local row with the same `client_id` instead of adding it twice. Playing still `PUT`s the library entry.
* **Book page caching**: `GET /v1/books/{asin}` is `private, no-store` (it mixes in `cached` and `usable`). The app has no OkHttp cache; the Room
  copy (`cache_entry`) is reused for 5 seconds only (`CatalogRepositoryImpl.BOOK_FRESH_MS`: the screen and the player opening the same book) and
  as the offline fallback (marked stale). The Book screen asks again whenever it is resumed (`BookViewModel.onResumed`, e.g. back from Settings
  after adding a debrid key). Discover and search keep their 5 minutes (`max-age=300`).
* **Downloads**: `LibraryEntry.finished_at` (server time the status became finished; stored as `library_entry.finishedAtMs`) drives "delete N days
  after finishing" and the LRU order of finished books; an entry without it (older server, cached before v1.1) falls back to `updated_at`. The
  player's own write into the library cache keeps the cached finish time, or stamps the moment it happened, until a refresh brings the server's.
* **Sign-out**: `DELETE /v1/me/devices/{own id}` answering 204 or 401 (the token is already dead) both mean signed out; any other answer, a hung
  or unreachable server also ends signed out, within a few seconds.
* **Databases**: `audiobooks_player.db` and `audiobooks.db` are version 2 with real migrations (`PlayerDb.MIGRATION_1_2`, `AudiobooksDb.MIGRATION_1_2`;
  `RoomMigrationTest`): progress, unsynced bookmarks, learned durations and the offline caches survive the update.

## Flows

* **Onboarding** (`feature/onboarding`): server URL (default `https://earmark.aresius.xyz`, changeable) -> "I have an invite code" / "First
  setup (instance password)" / "Link this device (link code)" -> display name + device name -> Debrid step (TorBox / Real-Debrid key,
  validated live by `PUT /v1/me/debrid/{provider}`, skippable) -> BitTorrent step (risk explained, switch "Allow BitTorrent downloads"
  default OFF; turning it on needs a confirmation; sets `p2p_enabled` and a coherent `source_mode` through `PATCH /v1/me`). A kill
  during the last two steps resumes there.
  **Continue with Google** shows first when `GET /v1/version` says `google_enabled` (hidden otherwise; explained when the device has no
  Google Play services). Invite and link codes are 26 base32 characters: the field accepts pasted text (case, spaces, dashes forgiven,
  counted live) and **Scan QR code** (Google code scanner, no camera permission; paste is the fallback).
* **Main**: Discover, Search, Library, Settings (bottom bar); Book detail (cover, people, series, runtime, description, ranked releases with
  format/size/seeders, cached badges per provider and "usable via" chips: Debrid / BitTorrent / needs a key). **Play** streams (or
  plays local files) with the chosen release ("Listen with this release", remembered per book; default = best release the user can stream, cached
  first), shows the file list, the catalog chapters and the needs-key / preparing / failed states. Next to Play, **Download** (per book and per
  release) shows its state (preparing, progress, paused, failed with retry, downloaded, delete).
* **Player**: full-screen (cover-derived dark page, whole-book wavy seek bar with chapter ticks, skip, chapters, speed, sleep timer, bookmarks), a
  mini player above the bottom bar on every screen, notification and lock-screen controls, playback with the screen off. Details:
  `../docs/DESIGN.md` ("Player (Phase 3)").
* **Settings**: playback (skip back/forward 10/15/30/45/60 s, smart rewind), account name, region, languages, preferred format, source mode, wallpaper colors (opt-in, Android 12+), debrid keys
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
   Manager). Try: Continue with Google -> "not linked" -> invite code -> joined; or First setup, then Settings > Link Google account.
   Link codes made in Settings work in the demo too.
4. Debrid key `badkeybadkey` is rejected (`bad_debrid_key`), any other key of 8+ characters is accepted; with a key, Play and Download work
   against the demo (`prepare` needs a few polls; the demo book streams from the local audio fixture, every other book serves 4 MB silent WAVs under
   `/cdn/` with Range support, see `DemoDownloads`; the debug FakeBackend is the only server involved). A search for `error` returns a 503 to show
   the error state. State lives in memory: it resets when the app process dies (a stored session then continues as the demo admin, with a TorBox key).

The demo backend speaks contract v1.1 too: `POST /v1/releases/{hash}/durations` (first writer wins, bounds checked; the stored lengths show up as `duration_ms`
on the book page, and the demo book's m4b release starts with 60 s parts reported), `intro_ms`/`outro_ms` on the chapters of the generated books, `finished_at`
on library entries, idempotent bookmarks with `client_id` (the book is added as saved when needed) and `Cache-Control: private, no-store` on the book page.

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
* Policy ordering: `PolicyGate` (decision + the one lock for policy changes and every torrent/session resume; `PolicyGateTest` stress-tests it) and
  `NetworkSwitches` (blocked = DHT/LSD/port maps/incoming/listen sockets all off, also at session start). Instrumented:
  `aSessionStartedWhileBlockedIsPausedAndSilent`, `aPolicyFlipDuringMetadataFetchLeavesNothingRunning`.
* Release ships `arm64-v8a` only; debug adds `x86_64` for the emulator (`ndk.abiFilters` per build type in `app/build.gradle.kts`).

## Player on an emulator

The debug FakeBackend streams a local audio fixture: the demo book **Demo: Three Short Parts** (3 min, 3 x 1 min parts, in the library as
"Continue listening") has real chapters; every other demo book plays a silent WAV served by the demo backend itself (the downloads' `/cdn/`). Cached releases are `ready` at once, the
64 kbit/s release is "preparing" for four polls (progress 20-80 %) first. The first-setup user has no debrid key (Play asks for one); a
process restart continues as the default admin WITH a TorBox key, so a reinstall during development streams at once.

```bash
cd app
python3 scripts/audio_fixture.py &            # generates 3 x 60 s MP3 with ffmpeg in /tmp/opencode/audio-fixture, serves them with Range
                                              # support on 0.0.0.0:8765 (the emulator sees it as http://10.0.2.2:8765) and logs every request
                                              # with its Range and whether an Authorization header came along (it never does)
./gradlew :app:installDebug                   # then: demo backend, First setup, Skip, Skip, restart the app, Continue listening -> play
```

The demo book can also be downloaded for offline use (from the same fixture); the other demo books download the silent WAV (4 MB per file).

Own emulator, never a shared one: `emulator -avd audiobooks_pixel -port 5570 -read-only -no-audio -no-window`, `ANDROID_SERIAL=emulator-5570`.

## Tests

`./gradlew :app:testDebugUnitTest`: JUnit + MockWebServer + Turbine (+ Robolectric for Room/WorkManager). API client (error parsing, retry,
Retry-After, refresh, sign-out (204 and 401 alike), token never sent to other hosts), the contract coverage test, DTO parsing (v1.1 fields), auth, repositories, session cleanup,
the updater, WCAG contrast of both palettes, the ViewModels (onboarding, debrid, BitTorrent, settings, Google account, admin, discover, search,
book, library, player; their `state` is a `combine().stateIn()` pipeline fed from OkHttp threads too, so a test never reads `state.value` right
after a write: it waits with `awaitState`, see `testutil/Rules.kt`), the Google flow state machine (fresh nonce + fresh ID token after every failed attempt, not linked -> join, link/unlink, 409),
the QR encoder, code normalisation. Player: `BookTimeline` mapping (randomised), chapter fallback, sleep timer, smart rewind / finished / speed rules,
`ReleaseChooser`, resume + sync queue and last-write-wins against a fake `PlayerDao`/server, bookmarks (client_id, lost answers), duration reporting and chapter alignment (intro/outro), the stream link (302/202/403/404/429/502, token
only to the server) on MockWebServer, the book loader (local-first, remembered release, offline chapters), shake detection, preferences. Downloads:
`core/download/*Test` (path safety, Range resume / atomic rename / 202 and JSON never saved / redirect without Authorization / error mapping, Wi-Fi
gating with a fake network, LRU eviction, orphans, per-user isolation, LocalFiles, TorrentSource with a fake engine, worker retry/backoff).
BitTorrent: policy / path / planner / settings tests (`core/torrent`).

`./gradlew :app:connectedDebugAndroidTest` (needs an emulator; set `ANDROID_SERIAL=emulator-<port>` so a physical device is never used) runs
`BookPlayerTest` (the session's chapter view on a real ExoPlayer over generated WAV files), `PlaybackDataSourcesTest` (the real data source against two
MockWebServers: Range, no token on the CDN) and `PlaybackServiceTest` (service + MediaController), plus the BitTorrent tests described above.

The app uses no regular expressions, so there is no `AppRegexIcuTest` (add one, run on an emulator, with the first regex).

## Sign in with Google

Credential Manager (`androidx.credentials` + `googleid`); `GetSignInWithGoogleOption` for the button (falls back to `GetGoogleIdOption`
with `filterByAuthorizedAccounts(false)` and no auto-select). The WEB client ID is `BuildConfig.GOOGLE_SERVER_CLIENT_ID`
(default in `app/build.gradle.kts`, override with `-PgoogleServerClientId=...`); the Android OAuth clients (package + debug/release signing
SHA-1) are registered in Google Cloud, nothing in code. Never log or store an ID token. Real sign-in needs a Google account on the device:
emulators and phones without one show "no Google account" (card stays, codes work).
