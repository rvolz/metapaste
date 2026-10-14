# Load smoke — measured numbers (Q45 NFR)

Script: [`scripts/load-smoke.sh`](../scripts/load-smoke.sh) — plain sh + curl
loops, no extra dependencies. Measure, don't assume: achieved rate and
latencies below are what the run produced.

## Run (2026-10-14, M7 acceptance run)

- Binary: `./metapaste` built by `./scripts/build.sh` (`jolt build`,
  self-contained, ~37 MB)
- Hardware: 4 cores, aarch64 (ARM64), 8 GB RAM, Linux (debian glibc),
  server and client on the same box, loopback (`127.0.0.1`)
- Workload: closed-loop create+read pairs — `POST /api/v1/items` (JSON,
  ~55-byte content) followed by `GET /api/v1/items/{name}/content`, 50/50
  read/write mix, 4 client workers, 60 s
- Target: 100 req/s sustained

| Metric                        | Value          |
|-------------------------------|----------------|
| Achieved throughput           | **104.3 req/s** (6264 requests / 60.05 s) |
| Errors (non-2xx / transport)  | **0**          |
| Latency p50                   | 5.2 ms         |
| Latency p95                   | **11.7 ms**    |
| Latency p99                   | 16.5 ms        |
| Latency max                   | 37.1 ms        |
| Server-side cross-check       | `http_requests_total` +6264 — exact match with client count |

Q45 asks for ≥100 req/s with p95 ≤250 ms for uploads ≤1 MiB: both targets
met with wide margin on 4 modest cores (p95 here is tiny payloads; the
250 ms budget is for 1 MiB uploads at the reverse proxy's keep-alive
defaults).

## Method notes (read before comparing numbers)

- Per-key rate limits are **raised for the run**
  (`METAPASTE_RATE_LIMIT_RPS=100000`, `METAPASTE_RATE_LIMIT_BURST=1000000`).
  The Q31 defaults (10 rps / burst 20) are a per-key policy knob — at the
  default no single key can generate 100 req/s by design; the NFR is about
  server capacity.
- The pacing driver subtracts its own fork/exec overhead (curl + awk +
  sleep ≈ 30 ms per iteration on this box, override with `LOAD_OVERHEAD`)
  so the *achieved* rate tracks the 100 req/s target; achieved is always
  measured from real request counts, and the server's
  `http_requests_total` delta is printed as an independent cross-check.
- Everything runs on one box over loopback; no TLS, no network jitter.
  Numbers are a floor for single-node capacity, not a WAN benchmark.

## Found and fixed by this smoke (2026-10-14, commit `847a6cd`)

The first shakeout runs (8 s) showed **59/304 creates failing with HTTP
500**. Root cause, found by probing the storage layer with 4 parallel
writers: the store shares ONE SQLite connection and `jolt-lang/db` passes
statements straight to SQLite, so concurrent `BEGIN IMMEDIATE`/`COMMIT`
interleaved — "cannot start a transaction within a transaction". Fix:
every statement runs under the connection lock, and the write transaction
holds it across the whole BEGIN..COMMIT window. A second issue surfaced:
`java.security.MessageDigest` (jolt-crypto) has the same lazy
first-touch provider race as `java.time.Instant` (fixed earlier in M7);
it is now warmed at namespace load. Both have regression coverage —
`storage_test.clj/concurrent-writes-and-reads-on-one-store` and the
60 s smoke above (0 errors).

## Reproduce

```sh
./scripts/build.sh                 # or point BINARY= at an existing binary
./scripts/load-smoke.sh            # 60 s, target 100 req/s, 4 workers
./scripts/load-smoke.sh 30 200 8   # 30 s at a 200 req/s target
```
