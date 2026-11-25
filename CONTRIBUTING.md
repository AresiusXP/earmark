# Contributing

Thanks for helping. Read [docs/DESIGN.md](docs/DESIGN.md) first (decisions and rules) and [AGENTS.md](AGENTS.md) (working rules).

## Build and test

```bash
# Server (Go 1.26+, Postgres only for the store tests)
cd server
gofmt -l . && go vet ./... && go test -race ./...     # store tests need TEST_DATABASE_URL, else they are skipped
../scripts/test-store.sh                              # runs them against a throwaway Docker Postgres

# Android app (JDK 17, Android SDK)
cd app
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug

# Helm chart
helm lint helm/earmark
```

## Changes

* **Conventional commits** (`feat(server): ...`, `fix(app): ...`, `docs: ...`): releases and the changelog are generated from them by
  release-please. Use the scopes `server`, `app`, `helm`, `api`, `ci` or none.
* `api/openapi.yaml` is the contract between server and app: change it in the same commit as the code.
* If code and `docs/DESIGN.md` diverge, update the design in the same change.
* **App strings** come from the generator: edit the tables in `app/scripts/` (English, Dutch and the Spanish files in `scripts/strings_es`)
  and run `app/scripts/gen_strings.py`; never hand-edit the generated `values*/strings.xml`. CI checks that they are in sync.
* Tests: table-driven, `-race` clean; per-user data stays scoped by `user_id`.
* YAML uses 2 spaces. Keep changes small and focused.

## Never commit secrets

No API keys, keystores, passwords, tokens or private URLs, not even in tests or history. Fixtures use obviously fake values. CI runs
gitleaks and `govulncheck`; run `gitleaks detect --source . --config .gitleaks.toml` before pushing if you can. If you committed a secret by
mistake, revoke it first, then tell the maintainer (see [SECURITY.md](SECURITY.md)).

## License

By contributing you agree that your contribution is licensed under the [Apache License 2.0](LICENSE).
