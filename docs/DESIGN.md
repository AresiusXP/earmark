# Design — stremio-audiobooks

Source of truth. Phase 0 decisions are marked **TBD(phase0)** until the experiment in
`docs/PHASE0.md` is done.

## Decisions taken

* Go server, Postgres, Helm/ArgoCD like tutti-frutti. Host `audiobooks.aresius.xyz`.
* Debrid: TorBox first (batch cache check), Real-Debrid second (learned availability).
* Metadata: Audible catalog search + Audnexus (ASIN ids), Open Library fallback.
* Sources: Knaben (audiobook category 1003000) first; generic Torznab later.
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

## Open

* Prowlarr in the cluster: decided in Phase 3.
