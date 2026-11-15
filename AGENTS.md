# AGENTS.md — stremio-audiobooks

Stremio addon serving audiobooks via debrid services (TorBox first, then Real-Debrid).
**Read `docs/DESIGN.md` first**; if code and design diverge, update the design in the same change.

## Rules

* Go, stdlib first, `log/slog`, table-driven tests, `-race` clean. Postgres only (no Redis).
* Never log debrid API keys or decrypted user config. Never put keys in URLs in clear text
  (the config in the addon URL is AES-GCM sealed).
* Private instance: not listed in the Stremio community catalog; public trackers/indexers only.
* Every route sends `Access-Control-Allow-Origin: *` (Stremio requires it).
* Stremio JSON arrays are never `null` (use `[]`).

## Deployment target

Homelab microk8s, GitOps via `AresiusXP/argocd-apps` (app-of-apps, Traefik, cert-manager
`letsencrypt`, external-dns Pi-hole, shared CloudPirates Postgres, never Bitnami).
Host `audiobooks.aresius.xyz`. Same release pipeline as tutti-frutti (release-please, ghcr images,
OCI Helm chart, `register` job). Client IPs are NOT visible behind Traefik (SNAT).

## Commands

```bash
# proto/phase0 (throwaway)
cd proto/phase0 && go vet ./... && go test ./... && go run . -addr :7000
```

## Conventions

Conventional commits (release-please). One version for the whole repo. YAML: 2 spaces.
