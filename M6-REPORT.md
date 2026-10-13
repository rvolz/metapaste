# M6 — health/management + observability — implementation report

Date: 2026-10-13 (UTC)
Branch: `m6` (worktree `workspace/metapaste-v2/metapaste-v2-wt/m6`)
Implementer: dirge, session `metapaste-m6-r6` (runs #1–#4 landed earlier
sessions; this run is the final chunk: observability remainder + report).
Workflow: REPL-driven (one live `jolt repl` inner loop; `./scripts/test.sh`
for final verification only; `jolt build` never run — shipping-only).

## Result: GREEN, NOT MERGED — awaiting Cabal acceptance

`./scripts/test.sh` exit 0: **124 tests / 2055 assertions / 0 failures /
0 errors** (the orchestrator's post-run-#4 baseline was 111/1998; this run
adds 13 tests / 57 assertions: 12 in `test/metapaste/observability_test.clj`,
1 regression in `metrics_test`). `scripts/run_tests.clj` auto-discovers
`test/**/*_test.clj`, so the new namespace needed no `scripts/test.sh` change.

## Delivered

Runs #1–#4 (already committed before this run):

- `a3b9435` config `metrics_port` (int, default main+1, `METAPASTE_METRICS_PORT`)
- `9bd36c4` storage helpers (`stats` `:api-keys-active`, `list-api-keys`,
  `migrations-current?`)
- `7649bc0` `metapaste.metrics` hand-rolled registry + exposition tests
- `fd89393` `/healthz` + `/readyz` (5 s-cached write probe, migrations-current,
  503 problem+json; chmod-unwritable recovery test on a temp dir)
- `f721201` api_test compile fixes
- `11389d7` `/manage/v1` full surface (admin-gated stats, cleanup trigger, keys
  CRUD with secret-once, item purge, redacted config) + partial call-handler
  metrics wiring
- `264ebac` stale integration assertion fix

Run #5 (this session):

- `981a7bf` Q47 metric wiring completion, `/metrics` internal listener,
  Q48/Q50 logging (see below)
- report commit (this file)

Content of `981a7bf`:

- `storage/expired-item?` (parameterized probe) + read-path expiry attribution
  in `get-item`/`get-content` -> `items_expired_total`.
- Registry fix: `items_expired_total` is no longer masked by the cleanup
  snapshot map (it previously rendered cleanup's counter over local series).
- Byte/lifecycle counters rewired through handler-attached `::log-fields`:
  `uploaded_bytes_total` = stored content bytes of creates (JSON and multipart
  alike), `downloaded_bytes_total` = served content bytes (byte bodies — the
  earlier wiring checked `string?` on a byte-array body and never counted),
  `items_deleted_total` = user DELETE + admin purge (key revocation excluded).
- `http_requests_total` now counts every dispatch outcome (auth 401/403/429,
  unmatched 404, method 405, handler 5xx) from the final status in one place;
  `storage_errors_total` no longer self-counts when bookkeeping fails after a
  successful handler call.
- `cleanup_duration_seconds` observed on every pass via a
  `metapaste.cleanup/set-observer!` hook wired by `metapaste.metrics` at load
  (no require cycle: cleanup never requires metrics).
- `GET /metrics` on a SEPARATE internal listener (below).
- Q48 request log line completed (below).

## /metrics binding decision: separate internal listener (Q46 option A)

- `metapaste.http/start-metrics-server!` runs a second `ring-chez-adapter`
  instance (`:strategy :threads`) whose handler is `api/metrics-handler`;
  bound to `127.0.0.1` on `:metrics_port` (default main+1), started by
  `serve!` alongside the main listener.
- The adapter made a second listener genuinely easy (two `run-server` handles
  in one process), so the admin-gated fallback was NOT taken — `/metrics` is
  not a route on the main listener at all (main answers 404).
- The listener serves exactly `GET /metrics` (Prometheus text 0.0.4,
  `text/plain; version=0.0.4`); everything else is 404/405 problem+json. No
  auth: it is loopback-only infrastructure (matching the spec's "separate
  internal listener" default); do not publish the port.
- Scrapes are logged (one `request` line, request_id per Q50) but NOT counted
  in `http_requests_total` — infrastructure, not API traffic.

## Metric semantics (Q47)

- Label values: `route`/`method`/`status`/`le` only — never item names or key
  ids (regression-tested).
- `items_expired_total` — read-path 404s attributable to expiry: GET metadata
  and GET content on an expired-but-uncollected name (detected with the
  parameterized `expired-item?` probe). NOT counted: unknown names, write-path
  404s (PATCH/DELETE/admin purge), and rows reaped by cleanup before any read
  — those appear in `cleanup_deleted_rows_total`. So expiry hits are detectable
  on the read path only; the counter is not a total count of expired items.
- `cleanup_runs_total` / `cleanup_deleted_rows_total` — sourced from
  `metapaste.cleanup/counters` at render time, so worker passes and manual
  `POST /manage/v1/cleanup` passes both count.
- `cleanup_duration_seconds` — one observation per completed pass (worker or
  manual), seconds, default buckets [0.001 … 60.0].
- `uploaded_bytes_total` — stored content bytes per successful create (the
  JSON envelope/multipart framing is not counted; both create kinds are
  uniform). `downloaded_bytes_total` — content bytes served per 200 content
  read (304s and metadata reads not counted).

## Logging (Q48/Q50) and gaps (feeds RQ7)

Done:

- JSON lines to stdout (`clojure.data.json`, hand-rolled in `metapaste.log`);
  UTC `ts`, `level`, `msg` always; `request_id`/`method`/`route`/`status`/
  `latency_ms` on every request line; `key_id` when authenticated; `name`
  (item) and `size` (stored/served bytes) as applicable. Secret-shaped keys
  redacted defensively.
- `X-Request-Id` accepted when present and printable ASCII 1–128, else a UUID
  is generated; echoed on every response (API and metrics listener) and
  attached to every request-scoped log line via dynamic binding.
- `route` in logs = matched route template (raw URI only for unmatched paths);
  `method` upper-case. Item names appear as `:name`, never as metric labels.
- Background cleanup pass lines carry `request_id "cleanup-job"` (chosen over
  omitting it — grep-ability). A manual `POST /manage/v1/cleanup` runs inline
  in its request, so any line it emits carries the triggering request's id.
- Leak tests over captured logs: item content marker, plaintext key secret,
  bearer header, and admin token all absent; request lines never carry a body.

Gaps (honest, for the RQ7 checkpoint):

- A caught handler exception logs only the generic 500 request line — the
  exception message/stack is deliberately never logged ("never leak
  internals"), so production 500s are not diagnosable from logs alone. A
  structured error line with a redacted message is an RQ7 candidate.
- Single stdout stream (no access/error separation) — per Q48 the platform
  owns retention/rotation.
- `name`/`size` only where applicable (create/download); list and metadata
  reads log `name` but no size.
- Unmatched-route log lines carry the raw request URI (client-typed); matched
  lines carry the template.

## Tests

- `test/metapaste/observability_test.clj` (12 tests): Q47 wiring driven
  through the real handler (delete/purge count, byte counts incl. multipart
  uniformity, dispatch-outcome counting, read-path expiry hits incl. the
  unknown-name negative case, cleanup histogram per pass); end-to-end scrape
  of the internal listener over a raw socket with regex exposition validation
  and the full Q47 name set; listener surface (only GET /metrics, main
  listener 404 for /metrics); request-id accept/generate/echo; Q48 field
  check; request_id on every captured line; cleanup-job request_id (worker
  driven to a failing pass against a throwing store); secret/content leak
  greps.
- `test/metapaste/metrics_test.clj` +1 regression (local `items_expired_total`
  series must render).

## Concurrency notes

- Metrics registry and cleanup counters are `swap!`-updated atoms: increments
  from request threads, the cleanup worker, and scrapes serialize per retry;
  no read-modify-write loss is possible. Histogram bucket choice is fixed at
  first observation (documented in the ns docstring).
- The cleanup observer runs inline on the pass thread; a scraping thread
  renders from its own snapshot (`@state` + `@counter-state`), so a render may
  interleave between counter bumps — acceptable for Prometheus counters.
- Readiness probe cache (`probe-cache`, ≤5 s) races benignly: last-write-wins
  on a fresh probe result.

## Not done (explicitly out of M6 scope)

- Q49 tracing (OTLP) — future milestone, untouched.
- Config hot-reload / admin UI — Q52 read-only config by design.
- Admin-gated `/metrics` fallback — not needed (separate listener works).
- Expiry attribution beyond the read path (see semantics above).
- `jolt build` — never run in this lane (per run deltas); binary build is the
  orchestrator's acceptance step.

Commit hygiene: run #5 commits carry no `Co-Authored-By` trailer (the earlier
runs' `Co-Authored-By: Claude Opus 4.8` trailers are bogus; left as history,
not repeated).
