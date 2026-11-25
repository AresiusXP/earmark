#!/usr/bin/env bash
# Runs the store integration tests against a throwaway Postgres container.
set -euo pipefail

NAME=abk-test-pg
PORT=${TEST_PG_PORT:-55432}
ROOT=$(cd "$(dirname "$0")/.." && pwd)

cleanup() { docker stop "$NAME" >/dev/null 2>&1 || true; }
trap cleanup EXIT
cleanup

docker run -d --rm --name "$NAME" -e POSTGRES_PASSWORD=test -p "$PORT:5432" postgres:17-alpine >/dev/null

# pg_isready can report ready during the init-phase temporary server, so require two
# consecutive successes over TCP.
ok=0
for _ in $(seq 1 60); do
  if docker exec "$NAME" pg_isready -h 127.0.0.1 -U postgres >/dev/null 2>&1; then
    ok=$((ok + 1))
    [ "$ok" -ge 2 ] && break
  else
    ok=0
  fi
  sleep 0.5
done
[ "$ok" -ge 2 ] || { echo "postgres did not become ready" >&2; exit 1; }

export TEST_DATABASE_URL="postgres://postgres:test@127.0.0.1:$PORT/postgres?sslmode=disable"
cd "$ROOT/server"
go test -race -count=1 "$@" ./internal/store/... ./internal/appapi/...
