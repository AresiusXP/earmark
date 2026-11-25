# Design — stremio-audiobooks

Source of truth. Phase 0 decisions are marked **TBD(phase0)** until the experiment in
`docs/PHASE0.md` is done.

## Decisions taken

* Go server, Postgres, Helm/ArgoCD like tutti-frutti. Host `audiobooks.aresius.xyz`.
* Debrid: TorBox (batch cache check) and Real-Debrid (re-added 2026-11-25 for the app, see "App direction"). Real-Debrid had been dropped on 2026-11-16; the Provider interface made it easy to bring back.
* Metadata: Audible catalog search + Audnexus (ASIN ids), Open Library fallback.
* Sources: Knaben (audiobook category 1003000) and AudioBook Bay (`sources/abb.go`, scraper: search page + detail page for the info hash; mirrors in `ABB_BASE_URLS`, `off` disables; queries must be lowercase or the site redirects to its front page; titles are sometimes scrambled by one letter, the matcher tolerates that). `TorrentsCSV` (`sources/torrentscsv.go`: torrents-csv.com JSON API, info hashes included; 1 req/s limiter, 30 min cache; `TORRENTS_CSV=off` disables) and `SolidTorrents` (`sources/solidtorrents.go`: the site now lives on bitsearch.eu, solidtorrents.to only redirects there; the free tier allows 200 requests per day, so results are cached for 6 h and the `x-ratelimit-*` headers are honoured: at <= 20 requests left, or after a 429, it answers `ErrRateLimited` until the reset time, fallback 1 h; `SOLIDTORRENTS=off` disables). Both search the title only (full-text AND on torrent names, many audiobook names lack the author) and drop ebook/video names and anything under 20 MB. Generic Torznab / Prowlarr only if coverage is still thin.
* Languages: English and Dutch (Audible marketplaces us/uk and nl/de as needed).
* Config sealed in the addon URL (AES-GCM, server secret); private instance.
* Prowlarr in the cluster: decided in Phase 3.

## Phase 0 results (2026-11-16, Stremio desktop)

Verified on desktop: custom type `audiobook` appears in Discover and the library, square cover
and detail page work, releases-as-seasons / files-as-episodes work, MP3 and M4B play (direct,
via `/resolve` 302, with and without `notWebReady`), big seeks work, resume position works,
next-chapter auto-play works, Continue Watching / Library reopen correctly.

* **Decided:** content type `audiobook` (custom). Book page = `videos` where season N is a
  release and episodes are its audio files (one entry for single-file M4B releases).
* **Decided:** `/resolve` may answer 302 to the debrid link. `bingeGroup` per release.
  `notWebReady` is not required (default hints work); keep it off.
* **Still open (Android):** the phone showed the category but playback could not be tested in
  the Android app (likely http on LAN). Re-test against the https deployment in Phase 1.
  Fallback if the custom type misbehaves on Android: also expose the same catalog as `series`
  (the ids are type-independent: `abk:<ASIN>`).
* ID format: `abk:<ASIN>` for books, `abk:<ASIN>:<season>:<episode>` for videos.

## Metadata findings (2026-11-16, live checks)

* Audible has no `api.audible.nl`: marketplaces that answer are com, co.uk, de, fr, it, es, ca,
  com.au, in. Dutch books live in every catalog (small); region `nl` maps to `us`/com
  (`metadata.MarketplaceRegion`), Audnexus knows Dutch ASINs only under `region=us`.
* Audible's `language=` parameter is ignored: Dutch search = Dutch keywords +
  `content_type=Product` + client-side filter on the `language` field. Dutch Discover seeds the
  keyword `verhaal`. Dutch coverage is thin: Open Library fallback matters for nl.
* `num_results` max 50, `page` 0-based. Knaben: `seeders` field, uppercase hex hash.

## Implementation notes (Phase 1)

* Layers: `httpapi` (Stremio protocol) -> `service` (`API`, see `service/contract.go`) ->
  `store` (Postgres), `metadata`, `sources`, `debrid/*`. `cmd/addon` wires them from env:
  `DATABASE_URL`, `CONFIG_SECRET` (>=32 bytes, rotating it invalidates every install URL),
  `PUBLIC_URL`, `INSTANCE_PASSWORD` (required unless `ALLOW_OPEN_CONFIG=true`), `PORT`,
  `ADMIN_PORT` (metrics, never exposed by the Ingress), `LOG_LEVEL`.
* Video id = `abk:<ASIN>:<infohash>:<fileIndex>`; season = rank of the release at page time.
  File index `-1` (AutoFile) = "provider picks the largest audio file" and is never guessed.
* `/resolve` answers 302 to the provider's signed CDN link (the API key never reaches the player);
  while the torrent is not ready, or the key is rejected, it serves ~10 s of silent MP3.
  `HEAD /resolve` has no side effects (it never adds a torrent).
* Manifest says `audiobook`; the sealed config in the URL is AES-GCM (`userconfig`).
* Series packs: popular series are mostly shared as one multi-book torrent ("Dungeon Crawler
  Carl Books 1-8"). `match.Score` rejects collections, except a pack that names the book's
  series (>= 0.8 token coverage), has a matching author surname, and the book is not itself a
  collection: then `Result.Collection` is true and the score is capped at 0.72 (single releases
  rank above packs). `book_releases.collection` stores the flag. `service.Book` shows a pack
  only when its files are known (stored or from the debrid cache check) and `match.FilterFiles`
  finds files of this book (by file/folder name: title words + series position "Book 2", "Vol. 5",
  "#3", "03 - Title"; a different explicit position never matches); `ReleaseView.Files` then holds
  only those files. Packs never get an AutoFile video; `Resolve(AutoFile)` refuses a torrent whose
  files look like several books (`match.IsPack`). Weak spot: a pack whose files are unknown
  cannot be shown, and the library has no per-hash "is a pack" lookup, so the guard on `Resolve`
  only works once the file list is known.
* Background listening: Stremio on Android stops audio when the screen turns off, so the addon
  also serves a private podcast feed per release. `GET /{cfg}/feed/{asin}.rss` (default = best
  release) and `GET /{cfg}/feed/{asin}/{ref}.rss` (`ref` = 40-hex info hash or 1-based season) return
  RSS 2.0 with `itunes:type serial`: one item per audio file in `ReleaseView.Files` order (a release
  without a file list gives one "Play full release" item, index -1), guid `abk:<asin>:<hash>:<idx>`,
  deterministic pubDates (2000-01-01 + position minutes) so apps keep the chapter order, enclosure =
  the `/resolve/{cfg}/{provider}/{hash}/{idx}/{name}` URL of the first configured provider. The sealed
  config is part of the URL, so a feed URL is a secret (anyone holding it can play from the user's
  debrid account) and stays valid until `CONFIG_SECRET` is rotated; feeds are `no-store` and, like
  every route, never logged by path. `GET /{cfg}/listen/{asin}` is a small self-contained page
  (nonce CSP, no scripts/styles from elsewhere; only the https cover image is loaded) listing the
  releases with copy-URL and one-tap links: `antennapod-subscribe://<full feed URL>` (AntennaPod
  strips the prefix, so the scheme must stay in or it assumes http), `pktc://subscribehttps/<host/path>`
  (Pocket Casts; `pktc://subscribe/` means http), `pcast://` and `podcast://`. The Stremio meta response
  links to the page ("Listen in a podcast app", category `Podcast`) whenever a release is playable.
  The page links hash-based feeds (stable); the default/season feeds follow the ranking at request
  time. Limits: a release that drops out of the top `MaxReleases` answers 404 on its hash feed; a
  file that is not cached yet makes `/resolve` serve ~10 s of silence, which an app that downloads
  episodes would keep.
* Known limits: Knaben alone finds few releases per book (more sources: Phase 3); file lists are
  stored per hash, not per provider; a stream's "not cached" label
  also shows when the cache state is simply unknown.

## Open

* Prowlarr in the cluster: decided in Phase 3.

## App direction (decided 2026-11-25)

Stremio is a poor audiobook player (stops on screen off, no chapters/speed/sleep timer, progress only
from its own player). The product becomes a standalone Android app, **Audiobooks**
(`app/`, package `xyz.aresius.audiobooks`, same monorepo and version as the server), with this server as its
backend. The Stremio addon routes keep working untouched until the app is in daily use (Phase 5 retires them).
`api/openapi.yaml` is the contract between server and app; change it first, in the same commit as the code.

### Model
* **Download-first.** Audiobooks are small, so the app downloads the files of a release to the phone and the
  player plays local files only. Debrid and BitTorrent are two ways to get the files onto the phone. Playback can
  start on file 1 while the rest downloads. No audio ever passes through the server or Cloudflare:
  `GET /v1/releases/{hash}/files/{index}/download` answers 302 to the provider's short-lived CDN link (never stored by
  the app) and `202` while the provider is still preparing it (instead of the silent MP3 of the Stremio route).
* **Users, not a shared instance.** `users` (admin|user). The instance password only creates the first admin
  (`/v1/auth/bootstrap`, refused once a user exists). Others join with single-use, expiring invite codes made by an
  admin; more devices join with single-use link codes. Each device has its own token pair (1 h HS256 JWT + rotating
  refresh token, only the SHA-256 of the refresh token stored); disabling a user or revoking a device stops access
  immediately (access tokens are checked against the device/user row). The JWT secret is derived from `CONFIG_SECRET`
  with HKDF (own label), like Echoes. Auth failures are limited globally (no client IPs behind Traefik).
* **Debrid keys belong to the user.** `user_debrid_keys(user_id, provider, sealed_key, fingerprint, status, validated_at,
  last_error)`; sealed with AES-256-GCM using an HKDF-derived key (own label, never the JWT key); never returned, never
  logged. A request that talks to a debrid service uses ONLY the requesting user's key; there is no fallback to another
  user's key. Two users may hold the same key (a household): `fingerprint = HMAC-SHA256(derived key, api key)`.
* **What is shared and what is per user.** Shared: books, releases, file lists, TorBox cache state (the TorBox cache is
  the same for every account, so one user's check helps everybody). Per user: debrid add/queue/links, RealDebrid probe
  results (stored per key fingerprint, never in the shared `availability` table), library, progress, bookmarks, settings.
  Add budgets / cooldowns / queue tracking are per key fingerprint, so a household shares one budget like it shares one
  account. Every query on a per-user table is scoped by `user_id` and has a store test proving user A cannot read or
  write user B's rows. Admin endpoints show whether a user has a key, never the key.
* **No key, no problem.** A user without a working key still sees every release; debrid actions answer `403 no_debrid_key`.
  `settings.source_mode` (auto|debrid|p2p) and `settings.p2p_enabled` are the USER's own choice (not an admin switch):
  BitTorrent on the phone is off until the user turns it on after an explicit warning (their IP is visible in the swarm;
  recommended with a VPN). The server only stores the two settings; the rules below are enforced in the app.
* **Real-Debrid** (`debrid/realdebrid`): add magnet -> select audio files -> poll `torrents/info` -> `unrestrict/link`.
  There is no batch cache check any more (`instantAvailability` is gone), so `CheckCached` returns (nil, nil); a book page
  shows RD cache state only after the caller's own key probed the release. Probing adds a torrent, so it happens only on
  `prepare` (an explicit user action), never while rendering a book page. RD may bind links to the IP that created them and
  limits parallel use; the provider's error message is shown as is.

### API shape (see `api/openapi.yaml`)
`/v1/auth/*`, `/v1/me` (+ `/devices`, `/debrid/{provider}`), `/v1/admin/*`, `/v1/search`, `/v1/discover`, `/v1/books/{asin}`
(+ `/chapters` from Audnexus), `/v1/releases/{hash}/prepare|files/{index}/download|torrent-files`, `/v1/me/library`
(+ bookmarks), `/v1/app/update*`. The `/v1` handlers live in `server/internal/appapi`; they call `service.API` (extended
with per-user key arguments) and never touch the Stremio `userconfig`. Errors are `{"error":{"code","message"}}`.
Client-reported torrent file lists (`torrent_files`, metainfo index) are stored apart from debrid file lists (`files`,
provider index); consumers match by path, never by index.

### App rules
* Kotlin, Compose, Material 3, Hilt, Room, Retrofit + kotlinx-serialization, Media3 (ExoPlayer + MediaSessionService),
  WorkManager, DataStore. Same Gradle/signing/update setup as Echoes (`/home/aresius/repo/echoes/app` is the template; copy
  patterns, not the music-specific code). Own signing key `/home/aresius/android/keys/audiobooks*` (USER MUST BACK UP).
  `versionCode = X*10000+Y*100+Z`. Build with `source /home/aresius/android/env.sh`.
* Player: one timeline per book over its ordered files; chapters from the server, falling back to one chapter per file when
  the Audible total differs from the files' total by more than 2 %. Skip back/forward 30 s (configurable), speed 0.5-3x per
  book, sleep timer (end of chapter / N minutes), bookmarks, resume (local every few seconds + on pause, synced to the server;
  last write wins on the client clock), MediaSession so lock screen, headset buttons and Android Auto work.
* Downloads: WorkManager, app-private storage, Wi-Fi only by default, storage limit and auto-delete after finish. A debrid
  file that is not ready is a visible "preparing" state, never saved as a silent placeholder.
* BitTorrent (Phase 4, libtorrent4j, arm64-v8a only plus x86_64 debug): downloads only the selected files, in order; stop
  seeding when done by default (optional seed to ratio 1.0, Wi-Fi + charging); "only when a VPN is active" setting enforced in
  the engine (pause every torrent when the VPN drops); first-use warning; mobile data off by default; honest "no seeders" /
  "stalled" states; foreground service with WorkManager resume (Android 15 caps dataSync at 6 h per 24 h).
* Visual design ("reading room": paper, ink, persimmon; Fraunces + Hanken Grotesk; cover-derived color; wavy progress): `app/DESIGN.md`.
* Every regex used by the app runs on Android's ICU engine in an instrumented test (`AppRegexIcuTest`). Test on an emulator
  against production responses before shipping. Never uninstall or `pm clear` the user's phone.

### Phases
0 contract + design (this section, `api/openapi.yaml`) -> 1 server (users/invites/devices, per-user keys, `appapi`, Real-Debrid,
library/progress/bookmarks, chapters, torrent-files) in parallel with 2 app skeleton -> 3 player + debrid downloads -> 4 BitTorrent
-> 5 polish, Android Auto, release pipeline (APK baked into the server image at `/update`), retiring Stremio.
