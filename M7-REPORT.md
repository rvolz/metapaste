# M7 — polish, packaging, tests — implementation report

Date: 2026-10-14 (UTC)
Branch: `m7` (worktree `workspace/metapaste-v2/metapaste-v2-wt/m7`)
Implementer: dirge, lane session (runs #1–#2 landed CORS + graceful shutdown;
this run fixed the flaky SIGTERM test at the root cause, finished packaging,
README, and the measured load smoke).

## Result: GREEN — all four done-criteria state results below

- `./scripts/test.sh` run 1: **exit 0 — 136 tests / 2112 assertions /
  0 failures / 0 errors**
- `./scripts/test.sh` run 2 (immediately after, no changes between):
  **exit 0 — 136 tests / 2112 assertions / 0 failures / 0 errors**
- The binary runs with **no Chez/jolt on PATH**: `env -i PATH=/usr/bin:/bin
  ./metapaste config check` exits 0; the container image is probed for
  `jolt`/`chez`/`scheme`/`chezscheme` on PATH by `scripts/docker-selftest.sh`
  (absent, checked in both directions).
- `README.md` exists and matches the actual config keys (all 16
  `METAPASTE_*` keys + `METAPASTE_CONFIG_FILE`); its quickstart build/run
  commands were executed verbatim as part of this run.
- `docs/load-smoke.md` holds **real measured numbers**: 104.3 req/s
  sustained (target 100), p95 11.7 ms, 0 errors, 6264 requests over 60 s,
  server `http_requests_total` cross-check exact.

## Delivered this run (commits)

1. `b8d49d9` — SIGTERM test flakiness fixed at the root cause. The child
   (`jolt -m app.core serve`) had TWO threads first-touching
   `java.time.Instant` concurrently; jolt's lazy time-provider registration
   (RFC 0014) made the loser throw — main thread lost ⇒ exit 1 before the
   startup line. Fix: `(defonce ... (java.time.Instant/now))` warms the
   provider at `metapaste.log` load, single-threaded, before any thread
   spawns. Verified 5/5 clean launches (zero "No dependency provides", 5×
   startup line, 5× exit 0 on SIGTERM) and 4× consecutive green shutdown
   test runs (deadline raised 30 s → 60 s for the ~24 s startup on this box).
2. `74ad85f` — `.gitignore`: `target/` (build/test output).
3. `76898a9` — `bind_host` config key (default `127.0.0.1`,
   `METAPASTE_BIND_HOST`): both listeners take the address from config —
   without it a container could not be reached through a published port.
4. `71905ea` — container packaging: multi-stage `Dockerfile` +
   `.dockerignore` + `scripts/docker-selftest.sh`. Stage 1 builds the
   deliverable **in-image** with `jolt build --closed-world` from pinned
   jolt v0.8.20 **source** on pinned threaded Chez 10.4.1 (built in-stage);
   a prebuilt-jolt install was impossible — **no jolt release ships an
   aarch64-linux asset for any tag** (checked v0.8.20 and nightly:
   x86_64-linux, aarch64-macos, aarch64-android only), and this box is
   arm64. Runtime stage: debian bookworm-slim (glibc 2.36, not musl),
   non-root uid 10001, `--read-only` root FS, exactly one writable volume
   (`/data`), EXPOSE 8080+8081, no jolt/Chez on PATH, and the only extra
   runtime libs are `libsqlite3-0` + `libssl3` (the two FFI libraries the
   binary dlopens; libtinfo comes with bash).
5. `847a6cd` — **the load smoke found two real bugs, fixed here**:
   - ~20% of parallel creates returned 500: the store shares ONE SQLite
     connection and jolt-lang/db passes statements straight through, so
     concurrent `BEGIN IMMEDIATE`/`COMMIT` interleaved ("cannot start a
     transaction within a transaction"). Fix: `with-write-tx` holds the
     connection lock across the whole transaction; all other statements go
     through locked wrappers; the readyz probe's BEGIN/ROLLBACK is one lock
     hold. Regression: `storage_test.clj/concurrent-writes-and-reads-on-one-store`
     (4 threads × 30 create+read pairs).
   - `java.security.MessageDigest` (jolt-crypto) has the same lazy
     first-touch race as `Instant`; warmed at `metapaste.storage` load.
   Interleaving argument: every statement on the shared connection now
   enters one monitor (`locking` on the conn object); the write tx holds it
   from BEGIN to COMMIT, so no second BEGIN (or step) can land inside an
   open transaction. SQLite-level reader/writer parallelism is sacrificed —
   acceptable at the 100 req/s design point (measured below).
6. `2241b3b` — `README.md` (what/quickstart binary+container/full config
   table/ops notes/API summary), `docs/load-smoke.md` (measured),
   `scripts/load-smoke.sh` (sh+curl driver: 4 workers, create+read pairs,
   achieved rate + p50/p95/p99/max, server-metrics cross-check),
   `scripts/build.sh` now derives `JOLT_CHEZ_CSV` from the running Chez
   (plain `./scripts/build.sh` was broken on symlinked-Chez hosts — the
   documented quickstart now works verbatim).
7. Report commit (this file).

## Docker evidence

`docker build -t metapaste:m7 .` exit 0; `scripts/docker-selftest.sh`
ALL PASS (exit 0) on the final tree: clean-volume start, `/healthz` +
`/readyz` 200, manage-key mint, paste create/read, `/metrics`, uid 10001,
no jolt/Chez on PATH (both directions verified), logs free of provider-race
errors, `docker restart` keeps data, `docker stop` (SIGTERM) exit 0 with
graceful-shutdown log lines.

## Load smoke summary

104.3 req/s sustained ≥ target 100 for 60 s, p95 11.7 ms (p50 5.2 / p99
16.5 / max 37.1), 0 errors, 4 client workers on 4 aarch64 cores/8 GB,
50/50 create/read mix, per-key rate limits raised (Q31 defaults cap any
single key at 10 rps by policy — documented in `docs/load-smoke.md`).

## Not done

- **OTLP tracing (Q49, stretch) not implemented.** The milestone marks it
  stretch, only after everything else is green; at zero new deps it needs a
  hand-rolled OTLP exporter plus a runtime HTTP client, which nothing in the
  runtime dep set provides (jolt.http-client is test-only). Stated rather
  than half-built.
- Range requests (Q24), admin UI (Q52), AV scanning/encryption (Q55) — out
  of scope by the milestone prompt.
- The docker build stage compiles on arm64; the Dockerfile is arch-generic
  (jolt source + Chez source) but was only exercised on aarch64-linux.
