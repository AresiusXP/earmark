# Phase 0 — does Stremio handle audiobooks?

Throwaway addon in `proto/phase0`: one public-domain book (LibriVox, Alice in Wonderland,
archive.org) exposed three ways, no debrid needed.

## Run

```bash
cd proto/phase0 && go run . -addr :7000
```

* Desktop / Stremio Web on the same machine: add `http://127.0.0.1:7000/manifest.json`.
  (Stremio Web needs https unless it is localhost; use the cluster/tunnel for that.)
* Phone / TV: the machine's LAN IP, `BASE_URL=http://<lan-ip>:7000 go run . -addr :7000`,
  then install `http://<lan-ip>:7000/manifest.json` (Android app allows http on LAN).

Catalogs appear under Discover: `audiobook` type, `series` type, `movie` type.
Each book has: MP3 release (12 chapters, season 1) and M4B (1 file, season 2).
Streams per file: direct URL, via `/resolve` (302), and M4B with `notWebReady`.

## Checklist (per client: desktop, Android, Android TV, Web)

For each of the three types:

- [ ] Type appears in Discover / shows catalog (custom `audiobook` is the risky one)
- [ ] Cover (square) renders; detail page opens
- [ ] Season picker: seasons shown, can switch between "MP3 release" and "M4B"
- [ ] MP3 chapter plays (direct)   [ ] same via /resolve (302)
- [ ] M4B plays: default hints / notWebReady / via /resolve
- [ ] Seek forward/back works (M4B 87 MB, check big jumps)
- [ ] Close and reopen: resume position remembered (per chapter)
- [ ] Next chapter auto-plays after one finishes (binge group)
- [ ] Item appears in Continue Watching / Library, reopens correctly
- [ ] Anything weird (video player UI for audio, black screen, subtitles prompts, speed control)

Send back the filled checklist (or just notes); the decision goes into `docs/DESIGN.md`.
