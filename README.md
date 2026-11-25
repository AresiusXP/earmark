<p align="center">
  <img src="docs/images/logo.png" alt="Earmark logo" width="128" height="128">
</p>

<h1 align="center">Earmark</h1>

<p align="center">
  A self-hosted <b>audiobook</b> app for Android and the server behind it. Earmark finds audiobook releases,
  plays them through your debrid service (TorBox, Real-Debrid) or, if you choose, BitTorrent on the phone, and keeps
  your place across devices: chapters, speed, sleep timer, bookmarks, offline downloads.
  It also still works as a <a href="https://www.stremio.com/">Stremio</a> addon (see below) until the app replaces it.
</p>

<p align="center">
  <a href="https://github.com/AresiusXP/earmark/actions/workflows/ci-server.yml"><img alt="CI" src="https://github.com/AresiusXP/earmark/actions/workflows/ci-server.yml/badge.svg"></a>
  <img alt="Go" src="https://img.shields.io/badge/Go-1.26-00ADD8?logo=go&logoColor=white">
  <img alt="Debrid" src="https://img.shields.io/badge/debrid-TorBox%20%C2%B7%20Real--Debrid-7b5bd6">
</p>

* **App:** `app/` (Kotlin, Compose, Media3), see `app/README.md` and `app/DESIGN.md`.
* **Server and API:** `server/`, contract in `api/openapi.yaml`; decisions and rules in `docs/DESIGN.md`.
* **Releases:** `docs/RELEASING.md`.

## The Stremio addon (legacy)

The addon below keeps working at the same host (the old `audiobooks.aresius.xyz` name is still served).

---

## Features

* **Audiobook catalog inside Stremio**: search by title or author, browse best sellers. Metadata (cover,
  authors, narrators, series, runtime) comes from Audible and Audnexus.
* **Instant playback through TorBox**: only releases TorBox already has cached are offered first (⚡).
  Anything else is added on demand. Real-Debrid works too, but has no cache check: its releases are
  added when you play them.
* **Books, not movies**: every release of a book is a *season*, every chapter file an *episode*. Stremio
  remembers your position per file and plays the next chapter by itself.
* **Smart matching**: release names are parsed and scored against the book (title, author, narrator,
  language, size). Radio dramas, abridged readings, collections of other authors and archive-only
  torrents are filtered out.
* **Series packs**: a torrent holding a whole series ("Books 1-8") is recognised, and only the files of
  the book you opened are shown.
* **Listen with the screen off**: every release is also a private **podcast feed**, so AntennaPod,
  Pocket Casts, Audiobookshelf or any podcast app can play it in the background and keep your progress.
* **English, Dutch and Spanish** book catalogs and release filtering.
* **Private by design**: your TorBox key is sealed (AES-256-GCM) into the install link; an instance
  password controls who can create links; no private trackers.

## How it works

```mermaid
flowchart LR
  S[Stremio] -- manifest / catalog / meta / stream --> A[Addon]
  P[Podcast app] -- RSS feed + enclosures --> A
  A -- books --> AU[Audible + Audnexus]
  A -- releases --> K[Knaben]
  A -- releases --> B[AudioBook Bay]
  A -- cache check, links --> T[TorBox]
  A <--> DB[(PostgreSQL)]
  S -- audio --> C[TorBox CDN]
  P -- audio --> C
```

1. You open a book. The addon looks up its metadata, searches the torrent sources (on first visit and
   when the data is stale), parses and scores the releases, and asks TorBox which are cached.
2. Stremio (or the podcast app) asks for a file. The addon answers with a redirect to a signed TorBox
   download link, so the audio never flows through the addon.

## Using it

See **[docs/USAGE.md](docs/USAGE.md)** for the listener guide: installing, finding books, the podcast
feed, and troubleshooting.

A running instance serves a configuration page at `/configure`. There you enter the instance password,
your TorBox key and your preferences, and get an install link for Stremio.

## Run it yourself

Requirements: Go 1.26+, PostgreSQL (tested with 17), a TorBox account.

```bash
cd server
export DATABASE_URL='postgres://user:pass@localhost:5432/audiobooks?sslmode=disable'
export CONFIG_SECRET="$(openssl rand -base64 48)"   # at least 32 bytes; rotating it invalidates every link
export INSTANCE_PASSWORD='choose-one'
export PUBLIC_URL='http://localhost:8080'           # the URL clients use to reach the addon
go run ./cmd/addon
```

Then open `http://localhost:8080/configure`. For use outside your own machine put the addon behind a TLS reverse proxy: Stremio Web needs
**https**, and phones reach the addon over the internet.

| Variable | Meaning |
|---|---|
| `DATABASE_URL` | PostgreSQL connection string (migrations run on start) |
| `CONFIG_SECRET` | Key for the sealed user config (≥ 32 bytes) |
| `PUBLIC_URL` | Public base URL, no path |
| `INSTANCE_PASSWORD` | Protects `POST /api/config`; required unless `ALLOW_OPEN_CONFIG=true` |
| `PORT` / `ADMIN_PORT` | Public port (default 8080) / internal metrics port (default 9090, `0` disables) |
| `ABB_BASE_URLS` | AudioBook Bay mirrors, comma separated; `off` disables the source |
| `LOG_LEVEL` | `debug`, `info`, `warn`, `error` |

A container image is built from `server/Dockerfile`, and a Helm chart lives in `helm/earmark`.

## Development

```bash
cd server
go vet ./... && go test -race ./...     # store tests need TEST_DATABASE_URL, else they are skipped
../scripts/test-store.sh                # runs the store tests against a throwaway Docker Postgres
TORBOX_API_KEY=... ../scripts/smoke.sh  # end to end against the real Audible, Knaben and TorBox APIs
```

```
server/
  cmd/addon          wiring, environment configuration
  internal/
    httpapi          Stremio protocol, podcast feed, configure and listen pages, /resolve
    service          application logic and the contract between layers (contract.go)
    store            PostgreSQL store and migrations
    metadata         Audible catalog + Audnexus client
    sources          Knaben and AudioBook Bay release sources
    parse, match     release-name parsing and book/release scoring
    debrid           provider interface, TorBox and Real-Debrid clients
    userconfig       AES-GCM sealed user configuration
docs/                design (source of truth), usage guide
helm/                Helm chart
```

* [docs/DESIGN.md](docs/DESIGN.md) is the source of truth for decisions and behaviour.
* [AGENTS.md](AGENTS.md) holds the working rules for contributors and coding agents.
* Conventional commits; releases are cut by release-please (one version for the image and the chart).

## Notes

* This addon only uses **public** indexes and never touches private trackers. You are responsible for
  what you stream: use it with content you have the right to listen to.
* The addon talks to the debrid service on behalf of the person whose key is sealed in the link, and
  nothing is stored on the server except public book and release metadata.
