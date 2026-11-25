# Changelog

## [0.6.0](https://github.com/AresiusXP/earmark/compare/v0.5.0...v0.6.0) (2026-11-25)


### Features

* **app:** remove the baked-in server URL from onboarding ([066966e](https://github.com/AresiusXP/earmark/commit/066966eadca17c52d2edfa50449657383fb9bb22))
* **helm:** neutral chart defaults, optional NetworkPolicy, cpu limit ([9e6c079](https://github.com/AresiusXP/earmark/commit/9e6c079db0299050d5a2622c73e8a8c6d2f96072))


### Bug Fixes

* **server:** apply the provider host allowlist to the Stremio /resolve redirect ([9c5e327](https://github.com/AresiusXP/earmark/commit/9c5e327d06b84e259c60c0969fdb4e4de792c540))
* **server:** rate-cap the Google nonce and app update routes ([561572b](https://github.com/AresiusXP/earmark/commit/561572bd9bc3e10312e1e29f150340e9db589871))


### Documentation

* add SECURITY.md and CONTRIBUTING.md ([f71c44d](https://github.com/AresiusXP/earmark/commit/f71c44d09a55a7a7f7ddae9f3c3d832a6f23b9c3))
* license the project under Apache-2.0 and list third-party notices ([c51077b](https://github.com/AresiusXP/earmark/commit/c51077b48907ed790a340d40dd19df041fd3559c))
* make the public docs generic and neutral about indexes and deployment ([641c47a](https://github.com/AresiusXP/earmark/commit/641c47a4b129548a0ee620535160411b9cbbe742))
* neutralize public deployment examples ([a6c4aa3](https://github.com/AresiusXP/earmark/commit/a6c4aa3c18d71af1605fc395afb0e12dbf6bba07))
* use neutral example public URL ([45acdab](https://github.com/AresiusXP/earmark/commit/45acdabc074ca00616f82d91789c0add05125a32))

## [0.5.0](https://github.com/AresiusXP/earmark/compare/v0.4.0...v0.5.0) (2026-11-25)


### Features

* **app:** complete Spanish appearance settings translations ([ccfbf45](https://github.com/AresiusXP/earmark/commit/ccfbf45285f1deabe4d220cd7702df4178141623))
* **app:** in-app theme choice (System/Light/Dark), optional light player, neutral splash ([53521b1](https://github.com/AresiusXP/earmark/commit/53521b1c1fd410fc6654a7432214a8dd54508f05))
* **app:** Spanish (es-AR) translation of every string ([12b8007](https://github.com/AresiusXP/earmark/commit/12b8007be4ceb19b7775c1341f1c6d6474b32d74))
* **app:** Spanish as a book language, Spanish greetings, demo books ([49304f0](https://github.com/AresiusXP/earmark/commit/49304f0f81e08fe4f4a19a32886073156b0505d8))
* **server:** detect, match and search Spanish releases (language tags, accent folding, folded source queries) ([aa4f953](https://github.com/AresiusXP/earmark/commit/aa4f9532cf347ccd3a6e6afb1231874fa6c976af))
* **server:** Spanish ('es') as a third book language in settings, legacy config, DB check and the configure page ([66592b0](https://github.com/AresiusXP/earmark/commit/66592b0a0732699096b7b10df0c7e092b729134b))
* **server:** Spanish catalog search and discover across the es and user marketplaces, book lookup falls back between es and us ([e7cdaae](https://github.com/AresiusXP/earmark/commit/e7cdaae76c0c38aacdd76c5b5d0b7ad6d85a8d87))


### Bug Fixes

* **app:** longer translations no longer clip (primary buttons, download rows, dark BitTorrent text) ([43b73e1](https://github.com/AresiusXP/earmark/commit/43b73e10097f994ad1ff4e039429da88cb15a44d))


### Documentation

* **app:** theme choice and the optional light player in DESIGN and README; fix segmented control border ([99ec1ec](https://github.com/AresiusXP/earmark/commit/99ec1ecae2fef317224a1f0cd9e696b1f4a29e96))
* Spanish language, marketplaces, Audnexus regions and release markers ([8f5b4fa](https://github.com/AresiusXP/earmark/commit/8f5b4fa381e5644645da648e8e2e5ae4288a65af))

## [0.4.0](https://github.com/AresiusXP/earmark/compare/v0.3.1...v0.4.0) (2026-11-25)


### Features

* **app:** 'Getting your book ready…' state with quiet progress, and the Book page warms the release Play would stream ([d820beb](https://github.com/AresiusXP/earmark/commit/d820bebbbc5b4c9d112bbbd4842fa82b8e49544f))
* **app:** API models, Retrofit service for every path of the contract, typed errors, retry and token refresh ([af037d2](https://github.com/AresiusXP/earmark/commit/af037d251c9e34d0efe9f50363b0c85080d0c93a))
* **app:** BitTorrent engine on libtorrent4j with in-engine safety policy ([fb1ec07](https://github.com/AresiusXP/earmark/commit/fb1ec07a20727c748e1253cf0c26184942927739))
* **app:** book page is no-store: 5 s copy only, refresh when the Book screen resumes ([6052683](https://github.com/AresiusXP/earmark/commit/60526831c0291b7b6ddbdd65445b9df424758b8a))
* **app:** book player core: Media3 service, one timeline per book, streaming data source, progress sync ([6349dbc](https://github.com/AresiusXP/earmark/commit/6349dbc79d44656223f50048237b0046aa32ba4a))
* **app:** contract v1.1 models and POST releases/{hash}/durations ([d4ad58a](https://github.com/AresiusXP/earmark/commit/d4ad58a41efcf34871014f47541870b598294ca6))
* **app:** debug-only in-process FakeBackend for running the screens without a server ([0de91d5](https://github.com/AresiusXP/earmark/commit/0de91d58da58ce01b2f035594d324b28ae40e2d8))
* **app:** define download and torrent contracts ([f523b7d](https://github.com/AresiusXP/earmark/commit/f523b7d229536c3dcc302ae4c0da1d32e6f0f0dd))
* **app:** demo backend speaks contract v1.1 ([01294f0](https://github.com/AresiusXP/earmark/commit/01294f0e6347ddd284abaee921179deb7acb7db5))
* **app:** design system, resources (English and Dutch), manifest and network security config ([bd71415](https://github.com/AresiusXP/earmark/commit/bd714155cc9795900cd44b6c99e0519a98fd9d03))
* **app:** downloads UI (book button, Downloads screen, storage settings, Library badge) ([6d73387](https://github.com/AresiusXP/earmark/commit/6d7338775b627df1bad4dd244ca1d93f75e09876))
* **app:** encrypted session store, repositories, Room cache and update manager ([e97a46e](https://github.com/AresiusXP/earmark/commit/e97a46ed20918fba8a22cd68fbb7e3009131de7c))
* **app:** full-screen player, mini player, release choice and play states, Settings &gt; Playback ([e5bffb9](https://github.com/AresiusXP/earmark/commit/e5bffb96a5f1b76077edcf9222b005ca41220da0))
* **app:** offline downloads engine (WorkManager, Range resume, storage limit, LocalFiles) ([f1bb7a2](https://github.com/AresiusXP/earmark/commit/f1bb7a2e3bb397e88984772743e1241f33b2c216))
* **app:** onboarding, discover, search, book, library, settings, admin users and update screens ([3391607](https://github.com/AresiusXP/earmark/commit/33916070fb8f8edef9abb9ca5576d389bd214495))
* **app:** pause and resume downloads (partial files kept, resumed with Range), Cancel still deletes ([286e431](https://github.com/AresiusXP/earmark/commit/286e431017a669b69a6decf99baeab50496b260d))
* **app:** release cards get real buttons: persimmon Listen, outlined Download, Show files in an overflow menu, badges in fixed places ([db0f595](https://github.com/AresiusXP/earmark/commit/db0f5958055ee53e561bede92cd8f72d222a010b))
* **app:** restore the last session's mini player after a process restart without starting playback ([39c0ad3](https://github.com/AresiusXP/earmark/commit/39c0ad3d6137da4e4b63b0221600b5237c37c914))
* **app:** server durations as the initial timeline, report learned ones, intro/outro in chapter alignment, idempotent bookmarks ([59e20e5](https://github.com/AresiusXP/earmark/commit/59e20e57f94069a1b78233a9558f42f4746dbe25))
* **app:** sign in with Google, QR/share/scan for invite and link codes, explicit-null preferred_provider ([5ea6b69](https://github.com/AresiusXP/earmark/commit/5ea6b69ccacc72a90e0cc717d8a65bb1ddbbe891))
* **app:** use finished_at for delete-after-finished and the LRU of finished books ([95e3d38](https://github.com/AresiusXP/earmark/commit/95e3d38d7a97f6206c95218c7983be12237eb177))
* **debrid:** add Real-Debrid provider ([d7af1dc](https://github.com/AresiusXP/earmark/commit/d7af1dcfd14e3bb814cefcd4105b1a5248cbf0bb))
* **server:** /v1 app API for auth, profile, devices, debrid keys and admin ([ae9831f](https://github.com/AresiusXP/earmark/commit/ae9831f5e5928bfe9f34ca042668ec3a68f73a36))
* **server:** /v1 catalog, release, library and app-update routes ([9f5b821](https://github.com/AresiusXP/earmark/commit/9f5b82198d49cc31fab13f64bd325718d722a9c0))
* **server:** accounts and per-user debrid key services ([3bbf898](https://github.com/AresiusXP/earmark/commit/3bbf8984afbf41ae151d26f9da17ffa3d1338154))
* **server:** bundled app update loader (APK + update.json) ([66cce99](https://github.com/AresiusXP/earmark/commit/66cce995c6286fd68b2e54674bf273782e2063b3))
* **server:** cache Audnexus chapters and report the runtime ([22e74cb](https://github.com/AresiusXP/earmark/commit/22e74cb371abbde6516d5375541f38691ce39ecf))
* **server:** catalog service with explicit per-user parameters for the /v1 API ([f6bfbab](https://github.com/AresiusXP/earmark/commit/f6bfbaba0970702530a0b8ef43a1ea85df841c9e))
* **server:** chapters carry optional intro_ms/outro_ms (Audnexus brand durations) ([a5a6c2e](https://github.com/AresiusXP/earmark/commit/a5a6c2e0b79c4435169a9f38db7a8129e07dd86d))
* **server:** contract text for codes, HEAD download and device sign-out; invalid_request responses with examples ([489dbd3](https://github.com/AresiusXP/earmark/commit/489dbd378a2ed50c421bbd8194d4f812477a2a4f))
* **server:** Google ID token verifier with JWKS cache and HMAC nonces ([07a6123](https://github.com/AresiusXP/earmark/commit/07a6123cb472995390c66994b4a84049ef5d09a7))
* **server:** idempotent bookmarks (client_id) and auto-add of the book as saved (migration 0013) ([db44a5c](https://github.com/AresiusXP/earmark/commit/db44a5cfdce41fd47ca0670bf0462d7f9d28c5b7))
* **server:** library entries carry finished_at (server time, migration 0012) ([0e54411](https://github.com/AresiusXP/earmark/commit/0e54411ec969dcf4bd048d6c3202a2f3934ed1cb))
* **server:** Postgres schema and store for accounts and debrid keys ([1912f54](https://github.com/AresiusXP/earmark/commit/1912f541f18d286a7bf65b2c59e3e6b28f5742e6))
* **server:** reported file durations (POST /v1/releases/{hash}/durations, duration_ms on book page files, migration 0014) ([34fea8c](https://github.com/AresiusXP/earmark/commit/34fea8c1dbe0b0f1e3b70b5eb6a71b49145f9a66))
* **server:** Sign in with Google as an additional sign-in method ([ee10354](https://github.com/AresiusXP/earmark/commit/ee10354e7fa1ea3619c0a3dcd1e4b53751bc7711))
* **server:** store per-user library, bookmarks, per-key availability and client file lists ([0c288c2](https://github.com/AresiusXP/earmark/commit/0c288c21f74f8d7704999bb40062090cbb0dd3e7))


### Bug Fixes

* **app:** a /v1/me refresh that was overtaken by a write no longer undoes it; document 204/401 sign-out ([3df1574](https://github.com/AresiusXP/earmark/commit/3df1574ddcdd0e0c23c47e290b89f7a43cd9d333))
* **app:** a 401 or a late refresh can no longer cross accounts or restore a signed-out session (requests and refreshes are bound to the sign-in) ([51eadaa](https://github.com/AresiusXP/earmark/commit/51eadaaf85ecb4d886635e216c215ddc1293bca3))
* **app:** a blocked torrent policy is applied before the session starts and atomically with every resume (no DHT/LSD/port maps/listen sockets, no resumed torrent while blocked) ([10c0071](https://github.com/AresiusXP/earmark/commit/10c007128f846cec6bd605a4203ccd82a159b1e8))
* **app:** a clock-skew 400 no longer discards queued progress (re-stamped with the server's Date and resent) ([5857a20](https://github.com/AresiusXP/earmark/commit/5857a2069f43a7c8c8c21f391b7574405ae67f96))
* **app:** a refused foreground service pauses downloads and torrents (visible waiting state, WorkManager retry) instead of running unprotected ([0874c50](https://github.com/AresiusXP/earmark/commit/0874c5097c649e1cbcdd319b694eb338b2c0ccfc))
* **app:** cover palette ignores the warm-hue filter, keeps dark pages warm, accent keeps the cover hue; warm demo covers ([cb1cc71](https://github.com/AresiusXP/earmark/commit/cb1cc71570b91d46606562630a6731e2b59d5547))
* **app:** Discover refreshes a saved copy when the app returns, the tab reopens or the network is back ([d7b284c](https://github.com/AresiusXP/earmark/commit/d7b284c0000b9a9f7bc2f38404e768e041c8fea1))
* **app:** eviction re-checks the playing book and active jobs per book at deletion time, inside the player's critical section ([a7681ed](https://github.com/AresiusXP/earmark/commit/a7681eda59c86a87e51aa44a150bc64188ed0634))
* **app:** Library filter chips scroll with an edge fade and the first row gets room under them ([9202ab2](https://github.com/AresiusXP/earmark/commit/9202ab2bb8248e448912e70babf9c36776d59fef))
* **app:** no client that carries API or auth traffic follows redirects; only the credential-free cdn client does ([afde998](https://github.com/AresiusXP/earmark/commit/afde998d2e7bbd0854bdf1679dbb1a0127faf334))
* **app:** only trusted controllers may connect to the exported media session or run its custom commands ([ddee9f8](https://github.com/AresiusXP/earmark/commit/ddee9f83c9a134695f8579a16b726126149c953e))
* **app:** player time labels read '-16:09:28 left', the book and the chapter are labelled, '~' only while lengths are estimated and explains itself ([f34e318](https://github.com/AresiusXP/earmark/commit/f34e31841ec82641687348dfa488e55fbb149cb2))
* **app:** Search keeps its query across tab switches and process death ([b520061](https://github.com/AresiusXP/earmark/commit/b520061459efe62d6571acd61f702933699d5159))
* **app:** the always-dark player no longer shows dark title, arrows and chapter buttons on dark in a light-theme phone ([30cf38b](https://github.com/AresiusXP/earmark/commit/30cf38b75fcd1d5cedc844ad6246e37153eb568c))
* **app:** the Continue listening hero toggles play/pause and opens the player once its book is loaded ([7721db8](https://github.com/AresiusXP/earmark/commit/7721db8dc5426ebf043ecb1545e77c30602e2389))
* **app:** the downloader accepts only audio content types (octet-stream only with a sniffed audio container) instead of a deny-list ([4230621](https://github.com/AresiusXP/earmark/commit/4230621d22a9658c06a4cc59f52d85444fc94f5c))
* **app:** the restored mini player follows library refreshes and never returns after the player is closed ([cfa74e6](https://github.com/AresiusXP/earmark/commit/cfa74e6c840516adc7d3452d308b952caa48a5aa))
* **helm:** expose PREPARE_ADDS_PER_HOUR, DEBRID_LINK_HOSTS, APP_UPDATE_DIR, APP_UPDATE_MAX_BYTES ([7383028](https://github.com/AresiusXP/earmark/commit/73830280345411bd1ca62bf83f932d250881a167))
* **server:** APK route keeps no-store, documents 206, bounds the APK size ([6ed28a1](https://github.com/AresiusXP/earmark/commit/6ed28a182bfb9dd8cdca4af62018686eb58eb7a8))
* **server:** bind debrid key fingerprints to the provider (v2 format, legacy rows count as unknown) ([de9d226](https://github.com/AresiusXP/earmark/commit/de9d226efc9dfd91b1591ed386e84cd19685e434))
* **server:** bound in-flight provider work and cancel abandoned flights ([64a8b89](https://github.com/AresiusXP/earmark/commit/64a8b895005da9caba90b3b186c87b6952f7dd5e))
* **server:** GET /v1/books/{asin} is private, no-store (it carries per-user cached/usable) ([c009073](https://github.com/AresiusXP/earmark/commit/c009073b71e659362ee3fb6e3c3e408bbc064d1f))
* **server:** global rate caps instead of failure lockouts, exact limiter refunds, in-transaction admin re-check ([277ab14](https://github.com/AresiusXP/earmark/commit/277ab14e6fa3a59b6b41fbcf3a36d92f54db710b))
* **server:** hard caps on the budget, cooldown and state maps ([0437daa](https://github.com/AresiusXP/earmark/commit/0437daa81889df32281364796f6609c22fa50cda))
* **server:** HEAD on a download only uses read-only provider calls ([286767c](https://github.com/AresiusXP/earmark/commit/286767ca42a7a91f1c3582acb6f67e7cd4c3f246))
* **server:** one cold-add flight per (key, provider, hash) for prepare and download ([30ee817](https://github.com/AresiusXP/earmark/commit/30ee817975b5c0eb440e6e915cfbc223df336fc4))
* **server:** only TorBox reads the shared availability table ([26d2830](https://github.com/AresiusXP/earmark/commit/26d2830a524db79b69fd8bd58f42eeef262220de))
* **server:** reject data after the first JSON value in request bodies ([49310c5](https://github.com/AresiusXP/earmark/commit/49310c5b62b32fcfdd1b5d74c3fba75455e90486))
* **server:** reject trailing JSON in torrent-files and JWKS bodies ([157fb1a](https://github.com/AresiusXP/earmark/commit/157fb1a03ae6a9b917f7d3faceac0a890f928f78))
* **server:** release_files first writer wins atomically, prune user_availability ([3ae8cb3](https://github.com/AresiusXP/earmark/commit/3ae8cb3410cd285c8155f6dd2ee866f00ebb4d85))


### Documentation

* **app:** contract v1.1 in the app (durations, chapter alignment rule, bookmarks, caching, finished_at, sign-out) ([e3b9633](https://github.com/AresiusXP/earmark/commit/e3b96336eeba7c151c1e433c88258fab339824e0))
* **app:** README and DESIGN for the polish round ([ccf881e](https://github.com/AresiusXP/earmark/commit/ccf881e77d5663671fc383bf3e84daa7b32aa33e))
* define Android app direction and API contract ([b104c76](https://github.com/AresiusXP/earmark/commit/b104c76ecd090d14793aaf00c74d895db355154d))
* describe accounts implementation and amend disable semantics ([481536d](https://github.com/AresiusXP/earmark/commit/481536d008a33ff4edcc66b936a61a208552a7e0))
* design notes for the review fixes (shared cache reads, cold flight, work gate, read-only HEAD, housekeeping) ([a9e8679](https://github.com/AresiusXP/earmark/commit/a9e8679bf7a56ce3670f174ce6de795823df5553))
* release pipeline (Delivery) and RELEASING.md ([e09d48c](https://github.com/AresiusXP/earmark/commit/e09d48c2f7da3d6ca153e0e0422c8fd3147e655f))

## [0.3.1](https://github.com/AresiusXP/stremio-audiobooks/compare/v0.3.0...v0.3.1) (2026-11-16)


### Bug Fixes

* clamp release metadata before int4 batch upserts ([f3b4a54](https://github.com/AresiusXP/stremio-audiobooks/commit/f3b4a54af153ad83a813696b8d2b502350c68543))

## [0.3.0](https://github.com/AresiusXP/stremio-audiobooks/compare/v0.2.1...v0.3.0) (2026-11-16)


### Features

* add torrents-csv and SolidTorrents release sources ([717b191](https://github.com/AresiusXP/stremio-audiobooks/commit/717b19152cdc6aaa9468b256170c5d13716daac1))

## [0.2.1](https://github.com/AresiusXP/stremio-audiobooks/compare/v0.2.0...v0.2.1) (2026-11-16)


### Bug Fixes

* expose podcast listening option in Stremio stream lists ([2ee4370](https://github.com/AresiusXP/stremio-audiobooks/commit/2ee437063d623ebb7807a1dfee8ef4ce638b4bec))


### Documentation

* overhaul README with project branding and setup guide ([e581e91](https://github.com/AresiusXP/stremio-audiobooks/commit/e581e91a05215c04723292f8f3d2100339639bec))

## [0.2.0](https://github.com/AresiusXP/stremio-audiobooks/compare/v0.1.0...v0.2.0) (2026-11-16)


### Features

* add private podcast feeds and listening pages ([bb43a02](https://github.com/AresiusXP/stremio-audiobooks/commit/bb43a027107164c06e9909436dff7ce727eefd81))
* add resilient AudioBook Bay source scraper ([ef426b5](https://github.com/AresiusXP/stremio-audiobooks/commit/ef426b5ac4a584995b4803129802ae98c9db792a))
* support series packs and resilient release searches ([24b3088](https://github.com/AresiusXP/stremio-audiobooks/commit/24b30886d66b7a6854fe19f77200eb7f6717dfec))

## 0.1.0 (2026-11-16)


### Features

* add Postgres-backed audiobook Stremio addon ([ac8a317](https://github.com/AresiusXP/stremio-audiobooks/commit/ac8a317b3eea165c666ba785bde5f3a2ca00c89c))
* add production deployment and release automation ([33630d7](https://github.com/AresiusXP/stremio-audiobooks/commit/33630d7053ce949e864f670ad9ea97c481cbeced))
* establish audiobook server foundation ([6d911f4](https://github.com/AresiusXP/stremio-audiobooks/commit/6d911f417583b045af8b3a68a2fd5674eb77e626))
* scaffold audiobook Stremio addon and Phase 0 prototype ([eae58d8](https://github.com/AresiusXP/stremio-audiobooks/commit/eae58d8b5300fde1ad5298edc492f72cf930cf47))
