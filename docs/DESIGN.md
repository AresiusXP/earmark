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
    fingerprint. `service.SharedCache(provider)` (TorBox only) is the single switch for BOTH directions: `setAvailability` ignores every other
    provider and so does the read (`sharedAvailability`, used by the Stremio `Book`/`Streams` path), so the Stremio `Resolve` path
    neither writes nor reads RD state of the shared table (it used to; behaviour change: RD streams in Stremio show "not cached"
    unless known another way). Migration `0011_availability_torbox_only.sql` deletes the legacy non-TorBox rows that older versions wrote. `usable.debrid` = providers whose stored key has status `ok`;
    `usable.p2p` = magnet present AND `settings.p2p_enabled` AND `source_mode != "debrid"`.
  * **Files.** `release_files` (debrid `files`) only ever holds TorBox indexes (first writer wins, RD lists are never
    stored; `Store.SaveFilesIfAbsent` checks and inserts in ONE statement under the per-hash advisory lock, so concurrent
    writers store exactly one complete list; all three writers use it, `SaveFiles` (replace) is no longer called); `release_client_files` (`torrent_files`) is written once per hash and never replaced. Consequence: the book
    page `files` are TorBox-indexed; for Real-Debrid the exact indexes come from `prepare`'s `files` (live from the user's
    account via `FileLister`), and the app matches by path. A series pack is shown when this book's files are found in
    either list (`files` or `torrent_files`); each list is filtered to this book's files.
  * **Chapters.** `metadata.Client.ChapterList` (Audnexus `/books/{asin}/chapters`) caches 24 h (misses 1 h, errors never) in a
    bounded in-memory map; `service.Chapters` asks the stored book's region first, then the caller's; unknown = empty
    `chapters` and the catalog runtime; an Audnexus outage is `502 upstream_failed`. Contract v1.1: `ChapterList` also carries
    Audnexus' `brandIntroDurationMs` / `brandOutroDurationMs` (cached with the list; negative or longer than the runtime = unknown), exposed as
    optional `intro_ms` / `outro_ms` (omitted when 0/unknown) so the app can align Audible marks with a release that has the branding cut.
    Audnexus' `isAccurate` is deliberately not exposed. `service.ChapterList` is the full result (`Chapters` is a thin wrapper).
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
    **One cold add per (fingerprint, provider, hash)**: the account lookup (RD `Files` / TorBox cache check), the budget spend, the
    failed-torrent retry and the provider's add call run in ONE flight (`app.cold`) shared by `prepare` and `download` of every file
    index; the flight leader's own file is resolved by the add call, every other caller resolves its file afterwards (no add, no
    budget) and shares the flight's early answers (cooldown, rejected key, failed, budget spent). Once the torrent is in the account
    nobody enters it any more.
    A ready answer (with `files`) is reused for 5 min without asking the provider. RD records `user_availability` (true when ready,
    false when a cold add came back pending). Concurrent calls of one key are coalesced (`flights`: one goroutine per key on a detached context bounded by 40 s and
    cancelled as soon as the last waiter has left, so abandoned work does not run on).
    **Bounded work and state**: every prepare/download/HEAD flight takes a unit of the work gate (`workgate.go`: 32 overall, 4 per key
    fingerprint; `AppDeps.MaxWork`/`MaxWorkPerKey`; joining a running flight is free); over it the request is `429 rate_limited` with
    `Retry-After`/`retry_after_s` (5 s). The budget and cooldown maps hold 4096 fingerprints each and are HARD caps: when full of active
    entries a NEW fingerprint is refused with the same 429 (never a silent eviction, which would reset that key's budget); the state map
    evicts only its least recently used entry.
    Unknown hash: 404 unless a `magnet` is given; the magnet must be well-formed (<= 2048 bytes, no control chars, every
    `xt=urn:btih` equals the hash, hex or base32); the stored release is rebuilt from the hash + sanitised `dn` (`source = "app"`),
    never the client's trackers, and never overwrites an existing row.
  * **download** (`releases.go`, `linkcache.go`, `linkhosts.go`). **The route serves both streaming and downloading**: with a debrid
    key the app mostly STREAMS from it (ExoPlayer follows the 302, seeks with Range requests, reconnects often) and downloads
    for offline use only when asked. Therefore: (1) the resolved link is cached in memory per (key fingerprint, provider, hash,
    index) for 10 minutes (capped per provider in `maxLinkTTL`, 1 h each: neither documents a precise validity), only for
    successful allowlist-checked links (never 202/failed/errors), evicted when the key is rejected, bounded (2048 entries), never
    logged; keyed by fingerprint so accounts never share a link (a household with one key does, like the account). (2) Concurrent
    requests of one key share one provider call. (3) `HEAD` has no side effects: a cached link answers 302; otherwise only the in-memory state of the key and the provider's
    read-only calls are used (optional `debrid.StatusReader`: RD `torrents/info` of the remembered id or the listing, TorBox `mylist`;
    else `FileLister`), NEVER `Resolve` (it adds, and RD's selects files), the cache check (stores state), `Delete`, budget or
    availability writes. A file the provider reports ready answers `200` (no `Location`: creating a link is not read-only; a `GET`
    redirects), anything that cannot be proven read-only `202 preparing`, a failed torrent (also from memory, never retried) `502`, a
    file missing from a ready torrent `404`. HEAD takes a work-gate unit like a GET. (4) `Location` must be https, default port, no
    userinfo and a host equal to / a subdomain of the provider table in `linkhosts.go` (TorBox `tb-cdn.st`, `torbox.app`, as in Echoes;
    RD `real-debrid.com`, `real-debrid.cloud`, `rdb.so`; extend with `DEBRID_LINK_HOSTS=realdebrid:cdn.example,...`), re-checked
    on every cached hit; anything else is `502 upstream_failed`, never a redirect. The response is `no-store`, an empty 302, and
    no log line carries a path, query or link. Status mapping beyond the contract: pending `202` + Prepare body (+ `Retry-After`
    in a cooldown), failed `502 upstream_failed` (message names the reason), file not in torrent `404`, add budget spent `429`.
  * **Body decoding**: every `/v1` body goes through `readJSON`/`readJSONLimit` (one value, the second `Decode` must be `io.EOF`; never
    `json.Decoder.More`, which lets a stray `}` through); the JWKS fetch uses `json.Unmarshal` for the same reason.
  * **torrent-files**: <= 2000 files, index unique >= 0, size >= 0, path normalised (`\` -> `/`, empty and `.` segments dropped), rejected
    when it has control chars/NUL, a `..` segment, invalid UTF-8 or > 512 bytes; body <= 2 MiB; stored once per hash; later calls
    get the stored list back; unknown release 404.
  * **durations** (contract v1.1, `releases.go` `durations`, migration `0014_release_file_durations.sql`): phones that decoded their files report
    `{path, duration_ms}`; table `release_file_durations(info_hash FK cascade, path, duration_ms CHECK 1000..172800000, PRIMARY KEY (info_hash, path))`.
    `model.NormalizePath` is the single path normaliser (also used by `torrent-files`); a reported path is stored only when its normalised form is that
    of an AUDIO file in `release_files` or `release_client_files` of the hash (anything else is counted in `ignored`, never stored), <= 2000 entries,
    one per normalised path, body <= 2 MiB, any out-of-range value is 400 for the whole request. `SaveFileDurations` is first-writer-wins
    (`ON CONFLICT DO NOTHING` under a per-release advisory lock, so overlapping reports cannot deadlock) and returns the stored values; a later differing report
    from another user is ignored. `service.withDurations` (optional `DurationStore` on the `UserStore`, /v1 book page only) sets `File.DurationMs` on copies
    of `files` and `torrent_files` by normalised path; Stremio is untouched. No reporter is recorded.
  * **Library**: every query carries `user_id`; PUT is an upsert of the whole entry with last-write-wins on `updated_at`
    (`WHERE stored.updated_at <= excluded.updated_at`: an equal timestamp is applied so retries are idempotent, an older write returns
    the stored entry); `updated_at` more than 24 h in the future is `400` (otherwise one wild clock would lock the other devices out);
    unknown ASIN -> `EnsureBook` (404 when Audible does not know it); `release_hash` must be a stored release (`400`);
    2000 entries per user / 500 bookmarks per book enforced under an advisory lock (`409 limit_reached`); `finished_at` (migration `0012_library_finished_at.sql`,
    server clock, `library.Write.Now`) is set when an applied write turns the status to finished, kept while writes keep it finished
    (`COALESCE(stored, new)`, so retries and equal-timestamp rewrites do not move it), cleared (null) on `saved`/`listening`; pre-existing
    finished rows were back-filled with their last write time; **Bookmarks** (contract v1.1, migration `0013_bookmark_client_id.sql`): optional
    `client_id` (`^[A-Za-z0-9_-]{1,64}$`, unique per (user, asin) through a partial unique index); `CreateBookmark` looks it up first, under the
    per-(user, book) advisory lock, so a retry returns the stored bookmark unchanged (200), counts once and is answered even at the 500 limit.
    A bookmark on a book outside the user's library makes the handler call `EnsureBook` (404 for an unknown ASIN) and `Store.EnsureSaved` (insert
    `saved` only when absent, same advisory lock and 2000 limit as `PutEntry`, `updated_at` 0 so any later client write wins LWW), then create it;
    deleting an entry
    deletes its bookmarks (FK cascade), deleting a user cascades everything. `user_availability` is keyed by fingerprint, not user, so
    it has no FK to `users` (its rows are harmless booleans a key can only read itself).
  * **App update** (`appupdate` + `appupdate.go`): modelled on Echoes: directory `APP_UPDATE_DIR` (default `/update`) with `update.json`
    (`version_name`, `version_code` = X*10000+Y*100+Z, `sha256`, `size`, `notes`, `released_at`) and `audiobooks.apk`, verified once at start
    (size + sha256; anything inconsistent = "no update", never an error). `GET /v1/app/update` (unauthenticated) answers
    `{available, version, version_code, size, sha256, url}` with `url = PUBLIC_URL/v1/app/update/apk`; the APK route supports Range (`206`, in the contract), is
    limited to 4 concurrent downloads (`503` + `Retry-After`) and is `no-store` like every other response (no override). `APP_UPDATE_MAX_BYTES`
    (default 200 MiB) is checked when the bundle is loaded: a larger APK (by `update.json` or on disk) means "no update", with a warning. The app compares `version_code` itself and verifies the sha256. The
    release pipeline that bakes the files into the image is Phase 5.
  * **Caching headers**: `search`, `discover` and `chapters` answer `private, max-age=300` on 200 only; `books/{asin}` answers
    `private, no-store` (it mixes the caller's `cached`/`usable` into shared data; contract v1.1, it used to be `max-age=300`, which
    showed a stale cache state right after a key change or prepare); everything else (the APK included) `no-store`.
  * **Housekeeping**: `cmd/addon/cleanup.go` deletes `user_availability` rows older than 30 days at start and every 6 hours (context-aware;
    readers ignore anything older than the availability TTL anyway, this only bounds the table).
  * New env: `PREPARE_ADDS_PER_HOUR` (default 30), `APP_UPDATE_DIR` (default `/update`), `APP_UPDATE_MAX_BYTES` (default 209715200),
    `DEBRID_LINK_HOSTS` (extra CDN suffixes). Helm values (plain, rendered only when set): `prepareAddsPerHour`, `appUpdateDir`,
    `appUpdateMaxBytes`, `debridLinkHosts`.
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
  Optional interfaces in `debrid`: `FileLister` (`Files(ctx,key,hash)`, never adds), `StatusReader` (`Status(ctx,key,hash)`: state,
  progress and files of a torrent in the account, strictly read-only; TorBox implements it too) and `Deleter` (`Delete(ctx,key,hash)`).

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
* Downloads: WorkManager, app-private storage, Wi-Fi only by default, storage limit and auto-delete after finish. A debrid
  file that is not ready is a visible "preparing" state, never saved as a silent placeholder.
* Downloads as built (Phase 3b, `app/.../core/download`, details in `app/README.md`): with a debrid key the player streams by default,
  "Download" is for offline use (per book; optional Wi-Fi auto-download of the book being played); BitTorrent books are always downloaded.
  One unique expedited WorkManager job per book (dataSync foreground notification, reschedules after the Android 15 timeout, survives
  reboot), per file: `prepare` polling (`retry_after_s`), the download route immediately before each file (the CDN link is never stored and
  the CDN request carries no Authorization), HTTP Range resume into `<file>.part`, size check against the server's size, atomic rename;
  202 and error bodies are never saved as audio. Files in `filesDir/books/<asin>/<hash>/`, rows (Room, scoped by user) in `downloads.db`;
  `LocalFiles` returns only complete files. Limit default 5 GB with LRU eviction of FINISHED books only (never the playing one), optional
  auto-delete N days after finishing, orphan cleanup, wipe on sign-out/user switch. Every server path is sanitized (no `..`, no absolute paths).
  The BitTorrent engine plugs in through `TorrentSource`/`TorrentEngine`.
* BitTorrent (Phase 4, libtorrent4j, arm64-v8a only plus x86_64 debug): downloads only the selected files, in order; stop
  seeding when done by default (optional seed to ratio 1.0, Wi-Fi + charging); "only when a VPN is active" setting enforced in
  the engine (pause every torrent when the VPN drops); first-use warning; mobile data off by default; honest "no seeders" /
  "stalled" states; foreground service with WorkManager resume (Android 15 caps dataSync at 6 h per 24 h).
* Accounts in the app: **Continue with Google** appears only when `GET /v1/version` has `google_enabled` (Credential Manager with a
  server nonce; an unlinked account is offered "join with an invite code", with a fresh nonce AND a fresh ID token for every attempt;
  first-setup admins link Google in Settings > Account, unlinking asks to confirm). Invite and link codes (26 Crockford base32
  characters) are normalised like the server does and shown as QR + blocks of five + Copy/Share + countdown; the sign-in screens take
  paste or Google's code scanner (no camera permission). Sign-out never waits for the server. `PATCH /v1/me` clears
  `preferred_provider` with an explicit JSON `null`.
* Visual design ("reading room": paper, ink, persimmon; Fraunces + Hanken Grotesk; cover-derived color; wavy progress): `app/DESIGN.md`.
* Every regex used by the app runs on Android's ICU engine in an instrumented test (`AppRegexIcuTest`). Test on an emulator
  against production responses before shipping. Never uninstall or `pm clear` the user's phone.

### BitTorrent engine (Phase 4, `app/app/src/main/kotlin/.../core/torrent`)
libtorrent4j 2.1.0-39 (`libtorrent4j` + `-android-arm64`, `-android-x86_64` debug only), behind `TorrentEngine` (`fetchFiles`, `download` as a cold
flow, `remove`); `LibTorrentEngine` is the only implementation, bound in `di/TorrentModule.kt` (also as `TorrentRuntime` for the service).
* **Session**: one `SessionManager`, started on first use, stopped 15 s after the last torrent stopped; DHT state in `noBackupFilesDir/torrent`.
  DHT, LSD, PEX, uTP, UPnP/NAT-PMP on while the policy allows. Not auto-managed: the engine runs its own slots (max active torrents 2) and
  libtorrent's reconnect wait is 10 s (a pause drops every peer).
* **Metadata**: `fetchFiles` adds the magnet in upload mode, waits up to 60 s, lists the files in metainfo order (BEP 47 pad files are skipped;
  `index` is the metainfo position) and stores a minimal `.torrent` (raw info dictionary) so the list never needs the network again; `download`
  fetches metadata first the same way when it is missing (a bare magnet with no wanted file is "finished" for libtorrent and drops its seeds).
* **Download**: only the selected files get a priority above 0, set when the torrent is added; the others are never created (pieces they share
  with a selected file go to libtorrent's part file `.<hash>.parts` inside the destination). Selected files are fetched in request order
  (priority 7, 6, ..., 1) and the first incomplete file gets piece deadlines when several are selected, so it is playable early. A file is
  complete only when all its pieces passed the hash check (`fileProgress` with piece granularity). **A single large M4B is playable only
  when it is complete** (the player plays complete files only). Progress every second, `Finished` after the data is flushed; `Stalled` after
  3 min without peers or 5 min without progress; `Failed` on disk errors / not enough space / unsafe torrent. Cancelling the collector pauses:
  fast-resume data (with the info dictionary) is saved every 30 s, on cancel and at the end, in `noBackupFilesDir/torrent/resume`, and a new
  collect resumes from the pieces on disk (also after process death: core/download re-collects). The magnet's `x.pe` peers and trackers are
  re-applied on resume.
* **Paths (zip-slip)**: libtorrent strips `..` and absolute components itself; the engine checks again what it reports (`TorrentPaths`: no `..`,
  no absolute or drive paths, no control characters or backslashes, component <= 255 bytes) and rejects the whole torrent on an unsafe path or
  a symlink; every selected path must resolve (canonically, so symlinks count) inside `destination`.
* **Safety policy** (`TorrentPolicy`, evaluated inside the engine on every settings / VPN / network / battery change):
  1. opt-in `p2p_enabled` (the server setting, mirrored locally so a resumed download after a cold start sees it): off -> the engine refuses
     (`fetchFiles` throws `TorrentBlockedException(Disabled)`, `download` emits `Failed`);
  2. "Only use BitTorrent when a VPN is active" (default ON): the app's default network (per-app, so split tunnelling is respected) must have
     `TRANSPORT_VPN`; when it drops, the whole libtorrent session is paused at once (all peer connections close) and DHT, LSD, port mapping
     and incoming connections are switched off; downloads emit `Paused(VPN_REQUIRED)` and continue when the VPN returns. The process is
     deliberately NOT bound to the VPN network (that would also move API and player traffic), so a leak-proof setup still needs the system's
     "Block connections without VPN" for the VPN app;
  3. Wi-Fi (or any unmetered network) only; mobile data needs "Allow mobile data" (`Paused(WIFI_REQUIRED)`, also used for "offline");
  4. battery < 15 % and not charging -> `Paused(LOW_BATTERY)`, resumes at 20 % or when charging;
  5. uploads: default is to stop sharing when the download finishes (the torrent is removed from the session); optional "seed to ratio 1.0"
     only on Wi-Fi while charging (and with the VPN rule), dropped as soon as one of those stops holding;
  6. caps: max connections (default 100), max active torrents 2, download / upload KiB/s limits; all in DataStore (`audiobooks_torrent`).
  Order of the reasons: opt-in, VPN, network, battery. `PauseReason.USER` is the notification's Pause / Stop (and the Android time limit).
* **Service**: `TorrentService`, foreground type `dataSync` (not `specialUse`), started by the engine when the first torrent starts, stops by itself
  5 s after the engine is idle; notification with speed, peers, ETA and Pause/Resume/Stop; a partial wake lock only while data flows. Android 15
  limits dataSync to 6 h per 24 h: `onTimeout` pauses everything (`Paused(USER)`), posts "open the app to continue" and schedules
  `TorrentResumeWorker` (WorkManager, 1 h, network + battery not low); the engine also resumes when the app comes to the foreground. If
  `startForeground` is refused (budget used up) the same path runs.
* **Tests**: policy state machine, paths, planner and stall detector, settings, options ViewModel (unit); a real two-session loopback swarm,
  service and timeout (instrumented, emulator). Not verifiable on an emulator: a real phone behind CGNAT / a real VPN app.

### Phases
0 contract + design (this section, `api/openapi.yaml`) -> 1 server (users/invites/devices, per-user keys, `appapi`, Real-Debrid,
library/progress/bookmarks, chapters, torrent-files) in parallel with 2 app skeleton -> 3 player + debrid downloads -> 4 BitTorrent
-> 5 polish, Android Auto, release pipeline (APK baked into the server image at `/update`), retiring Stremio.

## Delivery

One version (`vX.Y.Z`, release-please, `.release-please-manifest.json`) for the server image, the Helm chart and the APK.
Gradle reads that same manifest (`-PappVersion` overrides it), so `versionName` is the release and
`versionCode = X*10000 + Y*100 + Z` (each component 0..99 without leading zeros; checked by `release.yml`, Gradle and
`appupdate.VersionCode`). Steps and secrets: `docs/RELEASING.md`.

* **`release.yml`** (push to main, or `workflow_dispatch` with an existing tag): `release-please` -> `apk` -> `images` -> `chart` -> `register`.
  * `apk`: JDK 17, Android SDK, Gradle cache, `:app:assembleRelease -PappVersion=X.Y.Z` signed with the keystore decoded from the
    secrets (kept in `RUNNER_TEMP`, deleted at the end), `apksigner verify` (refuses the debug key; pins the certificate when the
    repository variable `ANDROID_CERT_SHA256` is set). Output: `audiobooks-X.Y.Z.apk` + `.sha256` uploaded to the GitHub release and,
    as workflow artifact `apk` (5 days), `audiobooks.apk` + `update.json` in the format of `server/internal/appupdate`
    (`version_name`, `version_code`, `sha256`, `size`, `notes` = release body capped at 4000, `released_at`).
  * `images`: waits for `apk` but **never depends on its success** (fail-open). It downloads the artifact into `server/update/`,
    re-checks version, sha256 and size, and bakes it in (`COPY update/ /update/`, `APP_UPDATE_DIR=/update`). A missing or inconsistent
    bundle gives a warning annotation and a job summary line "NOT included"; the image is still built and the server reports
    "no update bundled". The Dockerfile never uses `COPY . .`, and `server/update/*.apk|update.json` are git-ignored (only `.gitkeep`
    is tracked).
  * Recovery: `workflow_dispatch` with `tag`. `apk` rebuilds the APK of that tag; `images` skips an existing image tag (immutable)
    unless `force_images` is ticked, which is how an image published without the APK gets it later. A tag that predates the
    pipeline (its Dockerfile has no `COPY update/`) is built without the bundle.
  * `chart` and `register` are unchanged: the chart version must equal the tag, publishing is idempotent, and `register` only moves
    `targetRevision` in `argocd-apps` forward and skips pre-releases.
* **CI** is path-filtered: `ci-app.yml` (app/, `api/openapi.yaml`, the manifest) runs the strings-generator consistency check,
  unit tests, lint and `assembleDebug`; `ci-server.yml` (server/) and `ci-helm.yml` (helm/) do not run for app-only changes, and
  vice versa.
