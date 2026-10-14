#!/bin/sh
# Q45 NFR load smoke: sustained create/read traffic against the BUILT binary
# on localhost at a TARGET of 100 req/s for 60 s. Measures, never assumes:
# prints achieved req/s, error count, p50/p95/p99/max latency, and a
# server-side /metrics cross-check. Plain sh + curl loops — no extra deps.
#
# Usage: scripts/load-smoke.sh [duration_s] [target_req_s] [workers]
#   env: BINARY=./metapaste   PORT=18090  METRICS_PORT=18091
#
# Per-key rate limits are RAISED for the run (Q31 defaults are a per-key
# policy knob of 10 rps, not a server-capacity number); the mix is 50%
# writes (create) + 50% reads of the just-created item.
set -eu
cd "$(dirname "$0")/.."

DURATION=${1:-60}
TARGET=${2:-100}
WORKERS=${3:-4}
BINARY=${BINARY:-./metapaste}
PORT=${PORT:-18090}
METRICS_PORT=${METRICS_PORT:-18091}
BASE="http://127.0.0.1:$PORT"
ADMIN_TOKEN=load-smoke-admin

[ -x "$BINARY" ] || { echo "missing binary: $BINARY (run ./scripts/build.sh)" >&2; exit 1; }

WORKDIR=$(mktemp -d "${TMPDIR:-/tmp}/mp-load.XXXXXX")
SERVER_PID=""
cleanup() {
  [ -n "$SERVER_PID" ] && { kill -TERM "$SERVER_PID" 2>/dev/null || true; wait "$SERVER_PID" 2>/dev/null || true; }
  rm -rf "$WORKDIR"
}
trap cleanup EXIT INT TERM

# every iteration does 2 requests; iterations per worker per second =
# TARGET / 2 / WORKERS -> period in seconds. The period is then reduced by
# the driver's own fork/exec overhead (curl+awk+sleep ≈ 30 ms per iteration
# measured on the reference box; override with LOAD_OVERHEAD) so the ACHIEVED
# rate lands on target — achieved is still measured, never assumed.
OVERHEAD=${LOAD_OVERHEAD:-0.030}
PERIOD_RAW=$(awk -v t="$TARGET" -v w="$WORKERS" 'BEGIN{printf "%.4f", 2*w/t}')
PERIOD=$(awk -v p="$PERIOD_RAW" -v o="$OVERHEAD" \
  'BEGIN{v=p-o; if (v<0.01) v=0.01; printf "%.4f", v}')

echo "starting server: $BINARY (port $PORT, data $WORKDIR/data)"
METAPASTE_DATA_DIR="$WORKDIR/data" \
METAPASTE_PORT="$PORT" \
METAPASTE_METRICS_PORT="$METRICS_PORT" \
METAPASTE_BIND_HOST=127.0.0.1 \
METAPASTE_RATE_LIMIT_RPS=100000 \
METAPASTE_RATE_LIMIT_BURST=1000000 \
METAPASTE_ADMIN_TOKEN="$ADMIN_TOKEN" \
"$BINARY" serve > "$WORKDIR/server.log" 2>&1 &
SERVER_PID=$!

i=0
code=000
while [ $i -lt 60 ]; do
  code=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/healthz" || true)
  [ "$code" = "200" ] && break
  if ! kill -0 "$SERVER_PID" 2>/dev/null; then
    echo "server died at startup:" >&2; cat "$WORKDIR/server.log" >&2; exit 1
  fi
  i=$((i + 1)); sleep 1
done
[ "$code" = "200" ] || { echo "server not healthy (code=$code)" >&2; exit 1; }

key_json=$(curl -s -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"label":"load-smoke","scopes":"read,write"}' \
  "$BASE/manage/v1/keys")
KEY=$(printf '%s' "$key_json" | sed -n 's/.*"secret":"\([^"]*\)".*/\1/p')
[ -n "$KEY" ] || { echo "key mint failed: $key_json" >&2; exit 1; }

metrics_sum() {
  curl -s "http://127.0.0.1:$METRICS_PORT/metrics" \
    | awk '/^http_requests_total/{s+=$2} END{print s+0}'
}
BEFORE=$(metrics_sum)

worker() {
  id=$1
  lat="$WORKDIR/lat.$id"
  err="$WORKDIR/err.$id"
  : > "$lat"; : > "$err"
  while [ -f "$WORKDIR/go" ]; do
    raw=$(curl -sS --max-time 10 \
      -H "Authorization: Bearer $KEY" \
      -H 'Content-Type: application/json' \
      -d '{"content":"load-smoke probe line for p95 measurement","mime_type":"text/plain"}' \
      -w '
%{http_code} %{time_total}' "$BASE/api/v1/items" 2>>"$WORKDIR/curl.err" || true)
    last=${raw##*"
"}
    body=${raw%"
"*}
    t1=${last##* }
    printf '%s\n' "$t1" >> "$lat"
    name=${body#*\"name\":\"}; name=${name%%\"*}
    if [ "${last%% *}" != "201" ] || [ -z "$name" ]; then
      printf 'create:%s\n' "${last%% *}" >> "$err"
    fi

    raw=$(curl -sS --max-time 10 \
      -H "Authorization: Bearer $KEY" \
      "$BASE/api/v1/items/$name/content" -w '
%{http_code} %{time_total}' 2>>"$WORKDIR/curl.err" || true)
    last=${raw##*"
"}
    t2=${last##* }
    printf '%s\n' "$t2" >> "$lat"
    if [ "${last%% *}" != "200" ]; then
      printf 'read:%s\n' "${last%% *}" >> "$err"
    fi

    sleep_for=$(awk -v a="$t1" -v b="$t2" -v p="$PERIOD" \
      'BEGIN{s=p-a-b; if (s>0) printf "%.3f", s; else printf "0"}')
    sleep "$sleep_for"
  done
}

echo "load: ${DURATION}s, target ${TARGET} req/s, ${WORKERS} workers, period ${PERIOD}s/iteration (create+read)"
touch "$WORKDIR/go"
t_start=$(date +%s.%N)
w=1
W_PIDS=""
while [ "$w" -le "$WORKERS" ]; do
  worker "$w" &
  W_PIDS="$W_PIDS $!"
  w=$((w + 1))
done
sleep "$DURATION"
rm -f "$WORKDIR/go"
# wait ONLY the workers — a bare `wait` would also block on the server
wait $W_PIDS
t_end=$(date +%s.%N)

AFTER=$(metrics_sum)
ELAPSED=$(awk -v a="$t_start" -v b="$t_end" 'BEGIN{printf "%.2f", b-a}')

cat "$WORKDIR"/lat.* > "$WORKDIR/lat.all" 2>/dev/null || true
TOTAL=$(wc -l < "$WORKDIR/lat.all" | tr -d ' ')
ERRORS=$(cat "$WORKDIR"/err.* 2>/dev/null | wc -l | tr -d ' ')
SORTED=$(sort -n "$WORKDIR/lat.all")
printf '%s\n' "$SORTED" > "$WORKDIR/lat.sorted"

pct() { # pct N -> pN in ms (nearest-rank)
  awk -v p="$1" '{a[NR]=$1}
    END{ if (NR==0) {print "n/a"; exit}
         i=int(p*NR); if (i < p*NR) i++; if (i<1) i=1;
         printf "%.1f", a[i]*1000 }' "$WORKDIR/lat.sorted"
}
MAX=$(tail -1 "$WORKDIR/lat.sorted" | awk '{printf "%.1f", $1*1000}')
ACHIEVED=$(awk -v n="$TOTAL" -v e="$ELAPSED" 'BEGIN{printf "%.1f", n/e}')

echo
echo "==== load-smoke report ===="
echo "binary:            $BINARY"
echo "hardware:          $(nproc) cores, $(uname -m), $(awk '/MemTotal/{printf "%.0f GB", $2/1048576}' /proc/meminfo)"
echo "duration:          ${DURATION}s (elapsed ${ELAPSED}s), target ${TARGET} req/s"
echo "workers/period:    ${WORKERS} workers, ${PERIOD}s sleep budget per create+read iteration (driver overhead ${OVERHEAD}s subtracted)"
echo "requests:          total=$TOTAL errors=$ERRORS"
echo "achieved:          ${ACHIEVED} req/s"
echo "latency (ms):      p50=$(pct 0.50)  p95=$(pct 0.95)  p99=$(pct 0.99)  max=$MAX"
echo "metrics cross-check: http_requests_total +$((AFTER - BEFORE)) server-counted (client counted $TOTAL)"
[ "$ERRORS" -eq 0 ] || { echo "ERROR breakdown:"; cat "$WORKDIR"/err.* | sort | uniq -c; }
if [ "$TOTAL" -eq 0 ]; then exit 1; fi
