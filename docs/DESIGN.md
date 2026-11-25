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
  `ADMIN_PORT` (metrics, never exposed by the Ingress), `LOG_LEVEL`, and optionally `GOOGLE_CLIENT_ID` (+ `GOOGLE_CLIENT_IDS`,
  a comma list for a second audience; Sign in with Google, see Accounts notes; Helm: `googleClientId`, empty = off).
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
* Accounts (Phase 1a, `/v1`): packages `accounts` (users, invites, devices, link codes, JWT, limiters, middleware),
  `userkeys` (sealed per-user debrid keys), `appapi` (the `/v1` handlers) and `testpg` (test schema helper); the
  Postgres side is `store/accounts.go` + `store/userkeys.go` on migration `0003_accounts.sql`. `cmd/addon` builds them
  from `CONFIG_SECRET`/`INSTANCE_PASSWORD` (no new env var) and passes the `appapi.API` to `httpapi.Deps.V1`;
  `httpapi.ServeHTTP` routes every `/v1/` path there BEFORE it sets the Stremio CORS headers, so `/v1` never sends
  `Access-Control-Allow-Origin` and Stremio routes are unchanged.
  * Keys derived from `CONFIG_SECRET` with HKDF-SHA256, one label each: `audiobooks-jwt-v1` (access tokens),
    `audiobooks-keys-v1` (AES-256-GCM sealing of debrid keys; the sealed value is bound to `user_id\0provider` as AAD, so a
    row copied to another user does not open), `audiobooks-keys-v1/fingerprint` (HMAC key for the key fingerprint).
    Rotating `CONFIG_SECRET` therefore also logs every device out and makes stored debrid keys unreadable (status
    `invalid`, "set it again").
  * Tokens: access = HS256 JWT `{sub, dev, iat, exp}`, 1 h; refresh = 32 random bytes, only the SHA-256 stored, rotated by
    one `UPDATE ... WHERE refresh_hash = old` (a reused token finds no row and fails; it does not revoke the device).
    Invite codes and link codes (15 min) are 26 Crockford base32 chars = 130 bits (every secret except the instance
    password carries >= 128 bits), stored as the SHA-256 of the normalised code; a new link code replaces the user's
    earlier unused ones.
  * Revocation is immediate within the process: the middleware checks the device row (not revoked, belongs to the token's
    user) and the user row (not disabled) through a per-process cache of at most 5 s that `RevokeDevice`/`SetDisabled`
    clear at once. The deployment is one replica (Recreate); with a second replica a change would take up to ~5 s to
    show there. Every admin mutation (disable/enable, create/revoke invite) re-checks IN ITS OWN TRANSACTION, under a
    share lock on the actor's row, that the actor is an enabled admin (403 `forbidden` otherwise), so a stale Principal
    (an in-flight request, another replica's cache) cannot re-enable itself or mint invites. A store failure answers 503 `upstream_failed` (clients retry).
    **Disabling a user does not revoke its devices** (contract text amended): the flag blocks access tokens, refresh and
    link codes, and re-enabling restores the devices; otherwise a re-enabled user could never sign in again.
  * Bootstrap / disabling users take `pg_advisory_xact_lock` (`abk_user`) so "first admin only while zero users" and "never
    the last enabled admin" hold under concurrency; self-disable is refused (409). Invite redemption is
    `UPDATE invites SET used_at ... WHERE used_at IS NULL AND expires_at > now` in the same transaction that creates the user.
  * Limiters (global, in memory; client IPs are not visible behind Traefik, so every limit is process-wide and anybody
    who can send garbage could use a failure lockout to lock EVERYONE out). Therefore failures are only counted where the
    secret is guessable: `POST /v1/auth/bootstrap` (instance password; exists only while no user does) allows 5
    failures/min, and invite creation 10/min (an admin action). Each attempt reserves a slot and a success refunds
    exactly its own reservation. Refresh tokens (256 bit), invite codes and link codes (130 bit) and Google ID tokens
    cannot be guessed at any request rate, so those endpoints only get a generous global token-bucket cap that bounds CPU
    and database load and never looks at success or failure: refresh 20/s (burst 100), join, link and each Google
    endpoint 5/s (burst 20). Over the cap -> `429 rate_limited` with `Retry-After`/`retry_after_s`; a flood of garbage
    therefore delays real callers at most until the bucket refills (<= 1 s at these rates), it never locks them out.
    With `ALLOW_OPEN_CONFIG` and no `INSTANCE_PASSWORD`, bootstrap accepts any password (the operator asked for an open instance).
  * Request bodies of `/v1` are exactly one JSON value (<= 16 KiB): anything after it, even a stray `}`, is 400
    `invalid_request` (`appapi.decode`/`decodeOptional`; the legacy `/api/config` does the same).
  * Sign in with Google (additional sign-in method; packages `googleid` (stdlib only verifier, `googleidtest` fake Google for
    tests), `accounts/google.go`, `appapi/google.go`, `store/google.go`, migration `0010_google_identities.sql`; the migrator
    applies every missing version independently, so gaps in the numbering are fine). Enabled only when `GOOGLE_CLIENT_ID`
    (public web OAuth client ID, the audience; the app's Credential Manager request uses it as `serverClientId`) is set;
    otherwise every Google route answers 404 `google_disabled` and `GET /v1/version` has `google_enabled:false`. The app
    sends a Google ID token; the server only verifies identity (no OAuth redirect flow, no Google API access).
    * Rules: Google never creates an account by itself. A new user = admin invite + ID token (`POST /v1/auth/google/join`,
      invite redemption and identity link in ONE transaction: a taken identity fails the join and leaves the invite unused).
      The first admin comes from the instance password and may then link Google (`POST /v1/me/google`). `google_identities(sub
      PRIMARY KEY, user_id UNIQUE FK cascade, email, linked_at)`: one identity belongs to at most one user and a user has at
      most one identity (409 otherwise; relinking the same identity is a no-op). A disabled user cannot sign in with Google
      (401 `unauthorized`). `POST /v1/auth/google` with an unknown identity is 401 `google_not_linked`. Sign-in refreshes the
      stored email. Unlinking is ALWAYS allowed: the caller is signed in on a device, and devices, link codes and refresh tokens
      do not depend on Google, so Google is never the only way in (a user who revoked every device and unlinked Google needs a
      new invite, as one who never linked it would). `Me.google` is `{linked, email?}` (email only for the user itself); the admin
      list has `google_linked` and `google_email`. Display name of a Google join defaults to the given name, sanitised
      (control/format characters dropped, whitespace folded, 40 characters), falling back to full name, email local part, `Listener`.
    * Verification (`googleid.Verifier.Verify`): RS256 only (alg `none`/HS256 are refused before any key is looked up), key by
      `kid` from `https://www.googleapis.com/oauth2/v3/certs` cached honouring `Cache-Control: max-age` clamped to 5 min - 24 h,
      one refetch for an unknown `kid` at most once a minute, stale keys still serve known kids during a Google outage, RSA >= 2048
      bits; `iss` is `https://accounts.google.com` or `accounts.google.com`, `aud` one of the configured IDs, `exp`/`iat` with 2 min
      leeway, `email_verified` true (bool or "true"), `sub` set, and `nonce` a server nonce. A fetch failure is 502 `upstream_failed`.
    * Nonces: `GET /v1/auth/google/nonce` returns `random(16) || expiry` + HMAC-SHA256 (HKDF label `audiobooks-google-nonce-v1`),
      valid 5 min, stateless; single use is enforced by a bounded in-memory set (4096) filled ONLY after a token passed every other
      check, so unauthenticated callers cannot burn nonces; a restart forgets it (a replay still needs a live Google token and an
      unexpired nonce). The nonce is spent as soon as its token verified, so a later failure needs a new token. Tokens are never
      logged and never echoed in errors.
    * Limits: sign-in, join and link share a global request-rate cap of 5/s (burst 20), no failure lockout (see Limiters).
  * Debrid keys (`userkeys.Service`): `Put` validates with `Provider.Validate` first (rejected -> 400 `bad_debrid_key`,
    outage -> 502 `debrid_unavailable`, nothing stored), `Recheck` records ok|invalid|error without echoing provider
    text, `shared_with_others` is a fingerprint match computed in SQL, `GetForUse(userID, provider)` is the only way to
    read a key (returns a `Key` that prints as `[redacted]`; `ErrNoKey` -> 403 `no_debrid_key`, `ErrBadKey` -> 403
    `bad_debrid_key`). The provider list of `/v1/me/debrid` is the debrid registry, so `realdebrid` appears once it is
    registered in `cmd/addon`. An unknown provider in the path is 400 `invalid_request`.
  * Extension point for the catalog/library/release routes: `appapi.API.Extend(func(appapi.Router))` gives the `/v1` mux
    plus `Auth` and `Admin` middleware; inside a handler use `appapi.PrincipalFrom(r)`, `api.DebridKey(r, provider)`,
    `api.KeyError(w, err)`, `appapi.WriteJSON` / `appapi.WriteError`. Every `/v1` response defaults to `no-store`; the
    catalog handlers override it.
  * Tests: unit tests per package; store/appapi tests need Postgres (`scripts/test-store.sh` runs `./internal/store/...`
    and `./internal/appapi/...`).
* Catalog, releases, library, app update (Phase 1c, `/v1`): registered by `appapi.API.MountApp(AppDeps)` (files `app.go`,
  `catalog.go`, `releases.go`, `prepare.go`, `library.go`, `appupdate.go`; `cmd/addon` only builds the deps). Migration
  `0005_catalog_user_data.sql` adds `user_library`, `bookmarks`, `user_availability` and `release_client_files`
  (numbered 0005 so the Google sign-in branch can take 0004). New packages: `library` (types + storage port + limits) and
  `appupdate` (APK bundle loader). Postgres side: `store/library.go`.
  * **Service.** `service.BookFor(BookRequest)`, `SearchFor`, `DiscoverFor`, `EnsureBook`, `Chapters` take explicit
    per-user parameters (region, languages, format, the caller's keys); the Stremio `Book/Search/Discover` are thin
    wrappers over the same code (`bookParams`), so Stremio behaviour is unchanged. `ReleaseView.TorrentFiles` and
    `BookPage.SearchedAt` are new. The app page lists every release (nothing is hidden for being uncached, up to 20).
  * **Cache state per caller.** `cached[torbox]` comes from the shared `availability` table filled by a TorBox check made
    with the CALLER's own TorBox key (the TorBox cache is the same for every account); a caller without a working TorBox key
    gets no `torbox` entry (unknown). `cached[realdebrid]` is read only from `user_availability` by the caller's key
    fingerprint. `service.SharedCache(provider)` (TorBox only) is the single switch: `setAvailability` ignores every other
    provider, so the Stremio `Resolve` path no longer writes RD state into the shared table either (it used to; behaviour
    change: RD streams in Stremio show "not cached" unless known another way). `usable.debrid` = providers whose stored key has status `ok`;
    `usable.p2p` = magnet present AND `settings.p2p_enabled` AND `source_mode != "debrid"`.
  * **Files.** `release_files` (debrid `files`) only ever holds TorBox indexes (first writer wins, RD lists are never
    stored); `release_client_files` (`torrent_files`) is written once per hash and never replaced. Consequence: the book
    page `files` are TorBox-indexed; for Real-Debrid the exact indexes come from `prepare`'s `files` (live from the user's
    account via `FileLister`), and the app matches by path. A series pack is shown when this book's files are found in
    either list (`files` or `torrent_files`); each list is filtered to this book's files.
  * **Chapters.** `metadata.Client.ChapterList` (Audnexus `/books/{asin}/chapters`) caches 24 h (misses 1 h, errors never) in a
    bounded in-memory map; `service.Chapters` asks the stored book's region first, then the caller's; unknown = empty
    `chapters` and the catalog runtime; an Audnexus outage is `502 upstream_failed`.
  * **prepare** (`prepare.go`). Uses only the caller's key (`DebridKey` + `KeyError`; `ErrBadKey` from the provider calls
    `MarkInvalid`, drops the key's cached links and answers `403 bad_debrid_key`). The torrent is added at most once per key:
    in-memory state per (fingerprint, provider, hash) remembers "in the account" for 30 min; before the first add RD is asked
    read-only (`FileLister.Files`; found = no add) and TorBox gets a `CheckCached` with the caller's key (cached = free add, result stored
    in the shared table + files). A cold add spends the per-fingerprint budget (`PREPARE_ADDS_PER_HOUR`, default 30, sliding
    one hour; spent = `429 rate_limited` + `Retry-After`/`retry_after_s`, refunded when the provider rejected the call before
    adding). Provider rate limit = `200 {state: preparing, reason: debrid_cooldown, retry_after_s}` and the key is not
    called until the cooldown (provider's Retry-After, default 60 s, max 10 min) passes. `ErrFailed` = `failed` with
    `no_seeders` (status text says dead/stalled/seed) or `debrid_failed`; remembered 10 min (no provider call, no re-add), after
    that a prepare deletes the failed torrent (`Deleter`) and adds again on a new budget slot. File not in the torrent = `failed/not_found`.
    A ready answer (with `files`) is reused for 5 min without asking the provider. RD records `user_availability` (true when ready,
    false when a cold add came back pending). Concurrent calls of one key are coalesced (singleflight, detached 40 s context).
    Unknown hash: 404 unless a `magnet` is given; the magnet must be well-formed (<= 2048 bytes, no control chars, every
    `xt=urn:btih` equals the hash, hex or base32); the stored release is rebuilt from the hash + sanitised `dn` (`source = "app"`),
    never the client's trackers, and never overwrites an existing row.
  * **download** (`releases.go`, `linkcache.go`, `linkhosts.go`). **The route serves both streaming and downloading**: with a debrid
    key the app mostly STREAMS from it (ExoPlayer follows the 302, seeks with Range requests, reconnects often) and downloads
    for offline use only when asked. Therefore: (1) the resolved link is cached in memory per (key fingerprint, provider, hash,
    index) for 10 minutes (capped per provider in `maxLinkTTL`, 1 h each: neither documents a precise validity), only for
    successful allowlist-checked links (never 202/failed/errors), evicted when the key is rejected, bounded (2048 entries), never
    logged; keyed by fingerprint so accounts never share a link (a household with one key does, like the account). (2) Concurrent
    requests of one key share one provider call. (3) `HEAD` never adds: a cached link answers 302; otherwise RD is asked read-only
    (`Files`) and a torrent that is not in the account (TorBox: not remembered by this process) answers `202 preparing`; it does not
    run the cache check (which stores state) and does not retry failed torrents. (4) `Location` must be https, default port, no
    userinfo and a host equal to / a subdomain of the provider table in `linkhosts.go` (TorBox `tb-cdn.st`, `torbox.app`, as in Echoes;
    RD `real-debrid.com`, `real-debrid.cloud`, `rdb.so`; extend with `DEBRID_LINK_HOSTS=realdebrid:cdn.example,...`), re-checked
    on every cached hit; anything else is `502 upstream_failed`, never a redirect. The response is `no-store`, an empty 302, and
    no log line carries a path, query or link. Status mapping beyond the contract: pending `202` + Prepare body (+ `Retry-After`
    in a cooldown), failed `502 upstream_failed` (message names the reason), file not in torrent `404`, add budget spent `429`.
  * **torrent-files**: <= 2000 files, index unique >= 0, size >= 0, path normalised (`\` -> `/`, empty and `.` segments dropped), rejected
    when it has control chars/NUL, a `..` segment, invalid UTF-8 or > 512 bytes; body <= 2 MiB; stored once per hash; later calls
    get the stored list back; unknown release 404.
  * **Library**: every query carries `user_id`; PUT is an upsert of the whole entry with last-write-wins on `updated_at`
    (`WHERE stored.updated_at <= excluded.updated_at`: an equal timestamp is applied so retries are idempotent, an older write returns
    the stored entry); `updated_at` more than 24 h in the future is `400` (otherwise one wild clock would lock the other devices out);
    unknown ASIN -> `EnsureBook` (404 when Audible does not know it); `release_hash` must be a stored release (`400`);
    2000 entries per user / 500 bookmarks per book enforced under an advisory lock (`409 limit_reached`); deleting an entry
    deletes its bookmarks (FK cascade), deleting a user cascades everything. `user_availability` is keyed by fingerprint, not user, so
    it has no FK to `users` (its rows are harmless booleans a key can only read itself).
  * **App update** (`appupdate` + `appupdate.go`): modelled on Echoes: directory `APP_UPDATE_DIR` (default `/update`) with `update.json`
    (`version_name`, `version_code` = X*10000+Y*100+Z, `sha256`, `size`, `notes`, `released_at`) and `audiobooks.apk`, verified once at start
    (size + sha256; anything inconsistent = "no update", never an error). `GET /v1/app/update` (unauthenticated) answers
    `{available, version, version_code, size, sha256, url}` with `url = PUBLIC_URL/v1/app/update/apk`; the APK route supports Range, is
    limited to 4 concurrent downloads (`503` + `Retry-After`). The app compares `version_code` itself and verifies the sha256. The
    release pipeline that bakes the files into the image is Phase 5.
  * **Caching headers**: `search`, `discover`, `books/{asin}` and `chapters` answer `private, max-age=300` on 200 only; everything else `no-store`.
  * New env: `PREPARE_ADDS_PER_HOUR` (default 30), `APP_UPDATE_DIR` (default `/update`), `DEBRID_LINK_HOSTS` (extra CDN suffixes).
  * Tests: `appapi` unit tests with in-memory fakes (`relharness_test.go`: provider accounts per key, release/key stores, a log + body
    scan that fails the test when a key or link token leaks), Postgres tests for per-user isolation, LWW, limits, per-key availability,
    client files and the full HTTP stack (`catalog_pg_test.go`, `store/library_test.go`; run by `scripts/test-store.sh`).
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
* **Download-first (streaming-first with debrid since Phase 3, see "Player").** Audiobooks are small, so the app downloads the files of a release to the phone and the
  player plays local files only. Debrid and BitTorrent are two ways to get the files onto the phone. Playback can
  start on file 1 while the rest downloads. No audio ever passes through the server or Cloudflare:
  `GET /v1/releases/{hash}/files/{index}/download` answers 302 to the provider's short-lived CDN link (never stored by
  the app) and `202` while the provider is still preparing it (instead of the silent MP3 of the Stremio route).
* **Users, not a shared instance.** `users` (admin|user). The instance password only creates the first admin
  (`/v1/auth/bootstrap`, refused once a user exists). Others join with single-use, expiring invite codes made by an
  admin; more devices join with single-use link codes. Each device has its own token pair (1 h HS256 JWT + rotating
  refresh token, only the SHA-256 of the refresh token stored); disabling a user or revoking a device stops access
  immediately (access tokens are checked against the device/user row). The JWT secret is derived from `CONFIG_SECRET`
  with HKDF (own label), like Echoes. Only the guessable secret (the instance password at bootstrap) has a failure limiter; everything else has a global request-rate cap (see Limiters).
* **Debrid keys belong to the user.** `user_debrid_keys(user_id, provider, sealed_key, fingerprint, status, validated_at,
  last_error)`; sealed with AES-256-GCM using an HKDF-derived key (own label, never the JWT key); never returned, never
  logged. A request that talks to a debrid service uses ONLY the requesting user's key; there is no fallback to another
  user's key. Two users may hold the same key (a household): `fingerprint = "v2:" + HMAC-SHA256(derived key, provider NUL api key)` (the provider is part of the input and of the
  "shared with others" test; a row with an older bare-hex fingerprint counts as unknown until the key is next read for use or
  rechecked, which rewrites it).
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
  Implementation notes: `File.Index` is the RD file id (1-based). RD allows ONE file selection per torrent, so the first
  Resolve selects all audio files plus the requested one; `info.links` holds one link per selected file in ascending id
  order, so a file's link is found by its position among the selected files (a link/file count mismatch is an error, never
  guessed). Torrents are found by hash in `GET /torrents` (eventually consistent), so the id from `addMagnet` is remembered
  in memory per key fingerprint for 30 min to avoid duplicate adds. Requests are spaced 250 ms per key fingerprint and a
  429 blocks that key for `Retry-After` (fail-fast `ErrRateLimited`, no retries). The unrestricted URL must be https.
  Optional interfaces in `debrid`: `FileLister` (`Files(ctx,key,hash)`, never adds) and `Deleter` (`Delete(ctx,key,hash)`).

### API shape (see `api/openapi.yaml`)
`/v1/auth/*` (incl. `/v1/auth/google*`), `/v1/me` (+ `/devices`, `/debrid/{provider}`, `/google`), `/v1/admin/*`, `/v1/search`, `/v1/discover`, `/v1/books/{asin}`
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
  Layout and decisions of the built player: see "Player (Phase 3)" below.
* Downloads: WorkManager, app-private storage, Wi-Fi only by default, storage limit and auto-delete after finish. A debrid
  file that is not ready is a visible "preparing" state, never saved as a silent placeholder.
* BitTorrent (Phase 4, libtorrent4j, arm64-v8a only plus x86_64 debug): downloads only the selected files, in order; stop
  seeding when done by default (optional seed to ratio 1.0, Wi-Fi + charging); "only when a VPN is active" setting enforced in
  the engine (pause every torrent when the VPN drops); first-use warning; mobile data off by default; honest "no seeders" /
  "stalled" states; foreground service with WorkManager resume (Android 15 caps dataSync at 6 h per 24 h).
* Visual design ("reading room": paper, ink, persimmon; Fraunces + Hanken Grotesk; cover-derived color; wavy progress): `app/DESIGN.md`.
* Every regex used by the app runs on Android's ICU engine in an instrumented test (`AppRegexIcuTest`). Test on an emulator
  against production responses before shipping. Never uninstall or `pm clear` the user's phone.

### Player (Phase 3, built 2026-11-25)
Streaming-first: with a debrid key the player streams `GET /v1/releases/{hash}/files/{index}/download?provider=` (302 to the provider CDN,
Range requests, `202 Prepare` while the provider is busy); a complete local copy (`LocalFiles.localFile`, filled by the download and
BitTorrent branches) always wins, file by file; downloads stay optional. Code: `app/.../core/player` (service, model, data) and
`feature/player` (screens).
* **Service.** `PlaybackService` (Media3 `MediaSessionService`, foreground `mediaPlayback`, wake + Wi-Fi lock while playing) owns one
  ExoPlayer; `PlaybackCore` is its brain (load, ticker, sleep timer, smart rewind, persistence, errors). The UI never touches the player: it
  reads `PlayerHub` (a `StateFlow<PlayerState>`, same process) and sends commands through a `MediaController` (`PlaybackController`; book-level
  custom `SessionCommand`s in `PlayerCommands`: load, seek in the book, speed, skip, sleep start/cancel/extend, retry, close).
* **One timeline per book.** `BookTimeline(files, catalog runtime)` maps book position <-> (file, offset); unknown file durations are
  estimated (rest of the runtime by size) until ExoPlayer has prepared the file, then learned and stored (`file_duration`). One ExoPlayer item
  per file, next item preloaded. The media session sees a `BookPlayer` (`ForwardingSimpleBasePlayer`): the book as a playlist of CHAPTERS
  (position inside the chapter, next/previous = chapter, skip back/forward cross chapter and file borders), so lock screen, headset buttons
  and Android Auto speak chapters while the app's own seek bar covers the whole book.
* **Chapters.** `/v1/books/{asin}/chapters` marks are used as they are when the Audible runtime is within 2 % of the files' total (an estimated
  total counts), else one chapter per file (`ChapterSource.Files`, the sheet says why). Marks are not stretched: an intro or outro of unknown
  length shifts them by an unknowable amount. Embedded M4B chapters are not read (Media3 does not expose them).
* **Streaming.** `StreamLinkResolver` asks the server (authenticated client, no redirect following) and reads `Location`; the CDN hop is a
  PLAIN OkHttp client without the auth interceptor, so the token only ever reaches the server's host. 202 -> `Preparing` (visible state, the
  core polls `prepare` and resumes), 403 `no_debrid_key`/`bad_debrid_key` -> "Add a debrid key", other answers are typed errors; a broken stream
  becomes a Retry that resumes at the same place.
* **Resume and sync.** Room `audiobooks_player.db` (its own database: `progress`, `book_pref`, `file_duration`, `bookmark`; every user row is
  scoped by `userId` and wiped on sign-out). Position is written every 5 s while playing and on pause/stop/end; `PUT /v1/me/library/{asin}` at
  most every 30 s and at once on pause/stop (status listening/finished, `release_hash`, `position_ms`, `file_path` + `file_position_ms` (resume
  prefers the file position: it stays exact while durations are estimates), `speed`, `updated_at` = client clock). The `dirty` progress row is
  the offline queue (latest write per book; a WorkManager job retries when online; 400/404/409 are dropped). Opening a book takes the newer
  `updated_at` of the local row and the server entry. Finished = last 30 s (or 5 % of a short book) or 99 %; a finished book restarts from 0.
* **Controls.** Skip 10/15/30/45/60 s (Settings > Playback, separate back/forward), speed 0.5-3.0 on a 0.05 grid saved per book, sleep timer
  (end of chapter / 5-60 min, fades the last 10 s, shake or button extends; runs in the service), smart rewind (>1 min pause: 5/10/15 s back;
  also on resume from a saved position), bookmarks (local first, mirrored to `/bookmarks`, offline-safe).
* **Release choice.** `ReleaseChooser`: remembered choice per book > best ranked release the user can stream, cached first > best with local files.
* **UI.** Player (always dark, cover-derived colors, `docs/design/design-player.jpg`), mini player above the bottom bar, Discover hero and Library
  rows play, Book detail shows the chosen release, its files, the catalog chapters and the needs-key / preparing / failed states.

### Phases
0 contract + design (this section, `api/openapi.yaml`) -> 1 server (users/invites/devices, per-user keys, `appapi`, Real-Debrid,
library/progress/bookmarks, chapters, torrent-files) in parallel with 2 app skeleton -> 3 player + debrid downloads -> 4 BitTorrent
-> 5 polish, Android Auto, release pipeline (APK baked into the server image at `/update`), retiring Stremio.
