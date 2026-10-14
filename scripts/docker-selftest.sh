#!/bin/sh
# M7 docker self-test: build the image, run it the way the README documents
# (read-only root FS, single writable volume at /data, non-root, both ports),
# and prove the contract end to end:
#   - container starts on a CLEAN data dir and serves /healthz + /readyz
#   - a key can be minted via the manage API, a paste created and read back
#   - /metrics answers on the internal listener
#   - the process runs as uid 10001, with no jolt/chez/scheme on PATH
#   - logs carry no "No dependency provides" race errors
#   - docker restart keeps the data (named volume)
#   - docker stop (SIGTERM) exits 0 with the graceful-shutdown log lines
# Prints one PASS/FAIL line per check; exits nonzero on the first failure.
# Requires: docker, curl. SKIP_BUILD=1 reuses an existing image.
set -eu
cd "$(dirname "$0")/.."

IMAGE=${IMAGE:-metapaste:m7}
NAME=metapaste-selftest
VOL=metapaste-selftest-data
APP_PORT=${APP_PORT:-18080}
METRICS_PORT=${METRICS_PORT:-18081}
ADMIN_TOKEN=selftest-admin-token
BASE="http://127.0.0.1:$APP_PORT"

fail() { echo "FAIL: $*" >&2; exit 1; }
pass() { echo "PASS: $*"; }

cleanup() {
  docker rm -f "$NAME" >/dev/null 2>&1 || true
  docker volume rm "$VOL" >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM
cleanup

[ "${SKIP_BUILD:-0}" = "1" ] || {
  echo "docker build -t $IMAGE ."
  docker build -t "$IMAGE" . >/dev/null || fail "docker build"
}
pass "docker build ($IMAGE)"

docker run -d --name "$NAME" \
  --read-only \
  -v "$VOL":/data \
  -p "$APP_PORT":8080 \
  -p "$METRICS_PORT":8081 \
  -e METAPASTE_ADMIN_TOKEN="$ADMIN_TOKEN" \
  "$IMAGE" >/dev/null || fail "docker run"
pass "docker run --read-only -v $VOL:/data (uid + volume contract)"

# wait for the API (startup compiles nothing but must open the store)
i=0
while [ $i -lt 90 ]; do
  code=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/healthz" || true)
  [ "$code" = "200" ] && break
  i=$((i + 1))
  sleep 1
done
[ "$code" = "200" ] || fail "healthz never returned 200 (last=$code)"
pass "/healthz 200 on a clean data dir"

[ "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/readyz")" = "200" ] \
  || fail "/readyz not 200"
pass "/readyz 200"

key_json=$(curl -s -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"label":"selftest","scopes":"read,write"}' \
  "$BASE/manage/v1/keys")
SECRET=$(printf '%s' "$key_json" | sed -n 's/.*"secret":"\([^"]*\)".*/\1/p')
[ -n "$SECRET" ] || fail "manage key creation returned no secret: $key_json"
pass "POST /manage/v1/keys mints a key"

item_json=$(curl -s -H "Authorization: Bearer $SECRET" \
  -H 'Content-Type: application/json' \
  -d '{"content":"docker selftest paste","mime_type":"text/plain","description":"m7"}' \
  "$BASE/api/v1/items")
NAME_OF_ITEM=$(printf '%s' "$item_json" | sed -n 's/.*"name":"\([^"]*\)".*/\1/p')
[ -n "$NAME_OF_ITEM" ] || fail "create returned no name: $item_json"
meta=$(curl -s -H "Authorization: Bearer $SECRET" "$BASE/api/v1/items/$NAME_OF_ITEM")
printf '%s' "$meta" | grep -q '"description":"m7"' || fail "metadata read-back: $meta"
body=$(curl -s -H "Authorization: Bearer $SECRET" "$BASE/api/v1/items/$NAME_OF_ITEM/content")
[ "$body" = "docker selftest paste" ] || fail "content read-back: $body"
pass "create + read paste ($NAME_OF_ITEM)"

[ "$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$METRICS_PORT/metrics")" = "200" ] \
  || fail "/metrics on internal listener"
curl -s "http://127.0.0.1:$METRICS_PORT/metrics" | grep -q '^http_requests_total' \
  || fail "/metrics lacks http_requests_total"
pass "/metrics exposition on the internal port"

uid=$(docker exec "$NAME" id -u)
[ "$uid" = "10001" ] || fail "expected uid 10001, got $uid"
pass "runs as non-root uid 10001"

if docker exec "$NAME" /bin/sh -c \
    'command -v jolt || command -v chez || command -v scheme || command -v chezscheme'; then
  fail "jolt/Chez found on PATH in the runtime image"
fi
pass "no jolt/Chez/scheme on PATH in the runtime image"

logs=$(docker logs "$NAME" 2>&1)
printf '%s' "$logs" | grep -q '"msg":"metapaste serving"' || fail "no startup line: $logs"
if printf '%s' "$logs" | grep -q 'No dependency provides'; then
  fail "lazy time-provider race error in logs"
fi
pass "logs: startup line present, zero provider-race errors"

docker restart "$NAME" >/dev/null
i=0
while [ $i -lt 90 ]; do
  code=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/healthz" || true)
  [ "$code" = "200" ] && break
  i=$((i + 1))
  sleep 1
done
[ "$code" = "200" ] || fail "healthz after restart (last=$code)"
meta2=$(curl -s -H "Authorization: Bearer $SECRET" "$BASE/api/v1/items/$NAME_OF_ITEM")
printf '%s' "$meta2" | grep -q '"description":"m7"' \
  || fail "data lost across restart: $meta2"
pass "restart keeps the data (volume)"

docker stop -t 20 "$NAME" >/dev/null
exit_code=$(docker inspect --format '{{.State.ExitCode}}' "$NAME")
[ "$exit_code" = "0" ] || fail "SIGTERM exit code $exit_code, expected 0"
logs=$(docker logs "$NAME" 2>&1)
printf '%s' "$logs" | grep -q '"msg":"shutting down"' || fail "no shutting down line"
printf '%s' "$logs" | grep -q '"msg":"cleanup worker stopped"' || fail "no cleanup stop line"
pass "SIGTERM: exit 0 + graceful-shutdown log lines"

echo "DOCKER SELF-TEST: ALL PASS"
