# Third-party notices

Earmark itself is licensed under the Apache License 2.0 (see [LICENSE](LICENSE) and [NOTICE](NOTICE)).
The server container image and the Android APK redistribute the third-party components listed below, each
under its own license. This list covers what is shipped in release artifacts, not build or test tooling.

How it was compiled: Go licenses were read from the `LICENSE` files in the module cache for the packages
that `go list -deps ./...` reports for `server/`; Android licenses come from the `<licenses>` entry of each
artifact's POM (Google Maven / Maven Central) for the versions pinned in `app/gradle/libs.versions.toml`.
Transitive Android dependencies (AndroidX, Kotlin standard library, Google Play services base libraries)
are not enumerated one by one. Licenses marked *not verified* could not be confirmed from an authoritative
source. Versions change over time: regenerate this list when dependencies change.

## Server (Go)

Statically linked into the `earmark` image from the modules in `server/go.mod`.

| Module | License |
|---|---|
| github.com/jackc/pgx/v5, pgpassfile, pgservicefile, puddle/v2 | MIT |
| github.com/prometheus/client_golang, client_model, common, procfs | Apache-2.0 |
| github.com/beorn7/perks | MIT |
| github.com/cespare/xxhash/v2 | MIT |
| github.com/munnerz/goautoneg | BSD-3-Clause |
| golang.org/x/sync, golang.org/x/sys, golang.org/x/text | BSD-3-Clause |
| google.golang.org/protobuf | BSD-3-Clause |
| Go standard library and runtime | BSD-3-Clause |

The runtime image is `gcr.io/distroless/static-debian12` (Debian packages, each with its own license under
`/usr/share/doc/*/copyright` in the image: base-files, ca-certificates (MPL-2.0 and others), tzdata (public domain)).
The Prometheus modules (Apache-2.0) ship `NOTICE` files whose attribution is reproduced here as required:

> Prometheus instrumentation library for Go applications, data model artifacts, common libraries and procfs.
> Copyright 2012-2015 The Prometheus Authors. This product includes software developed at SoundCloud Ltd.
> (<http://soundcloud.com/>). Includes `perks` (<https://github.com/beorn7/perks>, Copyright 2013-2015
> Blake Mizerany, Bjoern Rabenstein) and Go support for Protocol Buffers (<http://github.com/golang/protobuf/>,
> Copyright 2010 The Go Authors).

## Android app (APK)

| Component | License |
|---|---|
| Jetpack Compose (UI, Foundation, Material 3, Material Icons Extended), Activity, Core, Navigation, Lifecycle, Room, DataStore, WorkManager, Palette, Core SplashScreen, Credentials, Hilt Navigation | Apache-2.0 |
| Media3 (ExoPlayer, Session, OkHttp data source) | Apache-2.0 |
| Dagger / Hilt | Apache-2.0 |
| OkHttp, Retrofit | Apache-2.0 |
| Coil 3 | Apache-2.0 |
| Tink (Android) | Apache-2.0 |
| kotlinx.serialization, kotlinx.coroutines, Kotlin standard library | Apache-2.0 |
| ZXing core | Apache-2.0 |
| Google Identity `googleid` | Android Software Development Kit License (per its POM; *not verified beyond the POM*) |
| Google Play services code scanner (ML Kit) | ML Kit Terms of Service (proprietary; a Google service, not open source) |
| Credentials Play Services Auth | Apache-2.0 (it calls Google Play services, which are proprietary and not bundled) |
| libtorrent4j 2.1.0-39 (Java bindings and native libraries) | MIT |

### libtorrent4j and the native code it bundles

`libtorrent4j-android-*` ships `libtorrent4j.so`, built from libtorrent master with these static components
(versions taken from libtorrent4j's `changelog.txt` and strings in the shipped binary):

| Component | License |
|---|---|
| libtorrent (Arvid Norberg) | BSD-3-Clause |
| Boost 1.90.0 (header libraries used by libtorrent) | Boost Software License 1.0 |
| OpenSSL 3.5.5 | Apache-2.0 |
| libdatachannel (WebRTC, used by libtorrent's WebTorrent support) | MPL-2.0 |
| libjuice (ICE, part of libdatachannel) | MPL-2.0 |
| usrsctp (part of libdatachannel) | BSD-3-Clause |
| plog (part of libdatachannel) | MIT |

The libdatachannel dependency list is taken from the symbols present in the binary and the license
reported by each repository on GitHub; other libdatachannel dependencies (for example libsrtp) may be
present and *were not individually verified*. Source code of the MPL-2.0 components is available at
<https://github.com/paullouisageneau/libdatachannel>, <https://github.com/paullouisageneau/libjuice> and
the libtorrent4j sources at <https://github.com/aldenml/libtorrent4j>.

### Fonts

Bundled in the app (`app/app/src/main/res/font`), licensed under the SIL Open Font License 1.1; the full
license texts are in `app/app/src/main/assets/licenses`.

| Font | Copyright |
|---|---|
| Fraunces | Copyright 2018 The Fraunces Project Authors (<https://github.com/undercasetype/Fraunces>) |
| Hanken Grotesk | Copyright 2021 The Hanken Grotesk Project Authors (<https://github.com/marcologous/hanken-grotesk>) |

## Not redistributed

Audible, Audnexus, the debrid services and the public indexes the server talks to are external services
with their own terms; none of their code or data is bundled.
