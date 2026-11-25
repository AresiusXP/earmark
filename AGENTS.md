# AGENTS.md — Earmark (repo: earmark)

Earmark: self-hosted audiobook app (`app/`, Android) and its Go backend (`server/`). Originally a Stremio addon serving audiobooks via debrid
services (TorBox, Real-Debrid); the Stremio routes stay until the app replaces them.
`api/openapi.yaml` is the server/app contract; update it in the same change as the code.
**Read `docs/DESIGN.md` first**; if code and design diverge, update the design in the same change.

## Rules

* Go, stdlib first, `log/slog`, table-driven tests, `-race` clean. Postgres only (no Redis).
* Never log debrid API keys or decrypted user config. Never put keys in URLs in clear text
  (the config in the addon URL is AES-GCM sealed).
* Per-user data: every query on a per-user table is scoped by `user_id`; a debrid call uses only the requesting
  user's key; keys are sealed, never returned. See docs/DESIGN.md "App direction".
* Private instance: not listed in the Stremio community catalog; public trackers/indexers only.
* Every Stremio route sends `Access-Control-Allow-Origin: *` (Stremio requires it); `/v1` (the app API) never does.
* Stremio JSON arrays are never `null` (use `[]`).

## Deployment target

Homelab microk8s, GitOps via `AresiusXP/argocd-apps` (app-of-apps, Traefik, cert-manager
`letsencrypt`, external-dns Pi-hole, shared CloudPirates Postgres, never Bitnami).
Host `earmark.aresius.xyz`. Same release pipeline as tutti-frutti (release-please, ghcr images,
OCI Helm chart, `register` job). Client IPs are NOT visible behind Traefik (SNAT).

## Commands

```bash
# proto/phase0 (throwaway)
cd proto/phase0 && go vet ./... && go test ./... && go run . -addr :7000
```

## Conventions

Conventional commits (release-please). One version for the whole repo. YAML: 2 spaces.
