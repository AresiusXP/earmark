#!/usr/bin/env bash
# End-to-end smoke test against the REAL Audible, Audnexus, Knaben and TorBox APIs.
# Starts a throwaway Postgres (docker) and the addon locally, walks
# configure -> manifest -> search -> meta -> stream -> resolve.
#
#   TORBOX_API_KEY=... scripts/smoke.sh            # no torrent is added to your account
#   TORBOX_API_KEY=... RESOLVE=1 scripts/smoke.sh  # also resolves (may ADD the torrent to TorBox)
#
# Optional: QUERY="project hail mary" (default), PORT=7100, KEEP=1 (leave addon/db running).
# The API key and the sealed config token are never printed.
set -euo pipefail

: "${TORBOX_API_KEY:?set TORBOX_API_KEY}"
QUERY="${QUERY:-project hail mary}"
PORT="${PORT:-7100}"
ADMIN_PORT="$((PORT + 1))"
PGPORT="${PGPORT:-55434}"
NAME="abk-smoke-pg"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TMP="$(mktemp -d)"
BASE="http://127.0.0.1:${PORT}"

cleanup() {
  if [[ "${KEEP:-}" != "1" ]]; then
    [[ -n "${ADDON_PID:-}" ]] && kill "$ADDON_PID" 2>/dev/null || true
    docker rm -f "$NAME" >/dev/null 2>&1 || true
  fi
  rm -rf "$TMP"
}
trap cleanup EXIT

step() { printf '\n== %s\n' "$*"; }
fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }

step "start postgres + addon"
docker rm -f "$NAME" >/dev/null 2>&1 || true
docker run -d --rm --name "$NAME" -e POSTGRES_PASSWORD=t -p "${PGPORT}:5432" postgres:17-alpine >/dev/null
until docker exec "$NAME" pg_isready -U postgres -h 127.0.0.1 >/dev/null 2>&1; do sleep 0.3; done
(cd "$ROOT/server" && go build -o "$TMP/addon" ./cmd/addon)
CONFIG_SECRET="$(head -c 48 /dev/urandom | base64 | tr -d '\n')" \
DATABASE_URL="postgres://postgres:t@127.0.0.1:${PGPORT}/postgres?sslmode=disable" \
ALLOW_OPEN_CONFIG=true PUBLIC_URL="$BASE" PORT="$PORT" ADMIN_PORT="$ADMIN_PORT" LOG_LEVEL=debug \
  "$TMP/addon" >"$TMP/addon.log" 2>&1 &
ADDON_PID=$!
for _ in $(seq 50); do curl -fs "$BASE/healthz" >/dev/null 2>&1 && break; sleep 0.2; done
curl -fs "$BASE/healthz" >/dev/null || { cat "$TMP/addon.log"; fail "addon did not start"; }

step "configure (validates the TorBox key against the real API)"
body="$(python3 -c 'import json,os;print(json.dumps({"keys":{"torbox":os.environ["TORBOX_API_KEY"]},"languages":["en"],"showUncached":True}))')"
code="$(curl -s -o "$TMP/cfg.json" -w '%{http_code}' -X POST "$BASE/api/config" -H 'content-type: application/json' --data "$body")"
[[ "$code" == 200 ]] || { echo "HTTP $code"; python3 -c 'import json,sys;d=json.load(open(sys.argv[1]));print({k:v for k,v in d.items() if k not in("token","manifestUrl","installUrl")})' "$TMP/cfg.json"; fail "POST /api/config"; }
TOKEN="$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["token"])' "$TMP/cfg.json")"
echo "ok (token ${#TOKEN} chars)"

step "manifest"
curl -fs "$BASE/$TOKEN/manifest.json" | python3 -c 'import json,sys;m=json.load(sys.stdin);print(m["name"],[c["id"] for c in m["catalogs"]])'

step "search: $QUERY"
q="$(python3 -c 'import sys,urllib.parse;print(urllib.parse.quote(sys.argv[1]))' "$QUERY")"
curl -fs "$BASE/$TOKEN/catalog/audiobook/abk-search/search=${q}.json" >"$TMP/search.json"
python3 - "$TMP/search.json" <<'PY' >"$TMP/asin"
import json,sys
metas=json.load(open(sys.argv[1]))["metas"]
for m in metas[:5]: print(m["id"], "-", m["name"], file=sys.stderr)
if not metas: sys.exit("no results")
print(metas[0]["id"])
PY
ID="$(cat "$TMP/asin")"; echo "using $ID"

step "meta (searches Knaben, checks TorBox cache)"
curl -fs "$BASE/$TOKEN/meta/audiobook/${ID}.json" >"$TMP/meta.json"
python3 - "$TMP/meta.json" <<'PY'
import json,sys
m=json.load(open(sys.argv[1]))["meta"]
print(m["name"], "|", m.get("runtime"), "|", len(m.get("videos") or []), "videos")
for v in (m.get("videos") or [])[:12]:
    print(f'  S{v["season"]}E{v["episode"]:<3} {v["id"]}  {v["title"]}  | {(v.get("overview") or "").splitlines()[0]}')
PY
VIDEO="$(python3 -c 'import json,sys;v=json.load(open(sys.argv[1]))["meta"].get("videos") or [];print(v[0]["id"] if v else "")' "$TMP/meta.json")"
[[ -n "$VIDEO" ]] || fail "no releases found for $ID"

step "stream"
curl -fs "$BASE/$TOKEN/stream/audiobook/${VIDEO}.json" >"$TMP/stream.json"
python3 -c 'import json,sys;[print(" ",s["name"],"|",s["title"].replace("\n"," / ")) for s in json.load(open(sys.argv[1]))["streams"]]' "$TMP/stream.json"
RURL="$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["streams"][0]["url"])' "$TMP/stream.json")"

if [[ "${RESOLVE:-}" == "1" ]]; then
  step "resolve (302 = playable link, 200 audio/mpeg = placeholder: still downloading)"
  curl -s -o /dev/null -D "$TMP/resolve.h" "$RURL" || true
  sed -n '1p' "$TMP/resolve.h"
  grep -i '^content-type' "$TMP/resolve.h" || true
  loc="$(grep -i '^location:' "$TMP/resolve.h" | sed 's/^[^:]*: *//' | tr -d '\r')"
  if [[ -n "$loc" ]]; then
    echo "redirect host: $(python3 -c 'import sys,urllib.parse;print(urllib.parse.urlparse(sys.argv[1]).netloc)' "$loc")"
    curl -s -o /dev/null -r 0-99 -w 'range probe: %{http_code} %{content_type} %{size_download} bytes\n' "$loc"
  fi
else
  step "resolve skipped (set RESOLVE=1; it may add the torrent to your TorBox account)"
fi

step "log check: key and token must not appear in the addon log"
if grep -qF -e "$TORBOX_API_KEY" -e "$TOKEN" "$TMP/addon.log"; then fail "secret found in addon log"; fi
echo "clean"
echo; echo "SMOKE OK"
