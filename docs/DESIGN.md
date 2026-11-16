# Design — stremio-audiobooks

Source of truth. Phase 0 decisions are marked **TBD(phase0)** until the experiment in
`docs/PHASE0.md` is done.

## Decisions taken

* Go server, Postgres, Helm/ArgoCD like tutti-frutti. Host `audiobooks.aresius.xyz`.
* Debrid: TorBox only (batch cache check). Real-Debrid was dropped on 2026-11-16 (the user no longer uses it); the Provider interface stays, so another service can be added later.
* Metadata: Audible catalog search + Audnexus (ASIN ids), Open Library fallback.
* Sources: Knaben (audiobook category 1003000) and AudioBook Bay (`sources/abb.go`, scraper: search page + detail page for the info hash; mirrors in `ABB_BASE_URLS`, `off` disables; queries must be lowercase or the site redirects to its front page; titles are sometimes scrambled by one letter, the matcher tolerates that). Generic Torznab / Prowlarr only if coverage is still thin.
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
