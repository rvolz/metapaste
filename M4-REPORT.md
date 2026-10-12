# M4 — TTL cleanup job — acceptance report

Date: 2026-10-12 (UTC)
Branch: `m4` (worktree `workspace/metapaste-v2/metapaste-v2-wt/m4`, base =
M3 merge f1ff7c5). Implementer: dirge (untrusted lane, `--session
metapaste-m4`, 3 one-shot runs), orchestrated/accepted by dev.

## Result: GREEN, NOT MERGED — awaiting Cabal acceptance

- `./scripts/test.sh` exit 0, run by the orchestrator after the final code
  change: **72 tests / 1708 assertions / 0 failures / 0 errors** (17 new on
  top of M3's 55).
- Acceptance build (orchestrator, once): `JOLT_CHEZ_CSV=$HOME/.local/chez-
  10.4.1/lib/csv10.4.1/tarm64le ./scripts/build.sh` exit 0, self-contained
  binary written (`metapaste`, ~36 MB).
- File allowlist respected: only `cleanup.clj` (new), `config.clj`,
  `storage.clj` (registry + 2 protocol members, per the run-2 ruling),
  `cleanup_test.clj` (new), `api_test.clj`, `config_test.clj`,
  `storage_test.clj`. No touches to openapi.yaml / deps.edn / http.clj /
  core.clj / AGENTS.md.

## Delivered (5 commits, all `M4: ` prefixed)

1. `39c4293` config `cleanup_interval_s` (int >= 1, default 3600, env
   override `METAPASTE_CLEANUP_INTERVAL_S`).
2. `223edf5` in-flight blob ref registry in storage (register BEFORE blob
   write, unregister after tx resolves incl. name-taken retry/failure) +
   `blob-referenced?` / `list-blob-files` — the orphan-sweep gates.
3. `a41045d` `src/metapaste/cleanup.clj`: `run-cleanup-once!` (500-row
   batch drain until empty, then orphan sweep), `start!`/`stop!` interval
   worker with `next-delay-ms` jitter (interval +/-10%, injectable rng),
   counters atom with the exact delta-5 key set. Includes the delta-7
   parallel write+cleanup no-race stress test (zero grace).
4. `d992050` expiry-semantics regression tests: expired-but-uncollected
   404s before any job run (metadata AND content), create-time
   `expires_at = created_at + ttl` (2 s tolerance), PATCH recompute-from-
   now (not from creation), `ttl 0` + `expires_at NULL` only when
   `allow_never_expire=true`, PATCH clamp -> 400.
5. `ac2ebb0` `start!` prunes a worker left registered after a timed-out
   `stop!` join (found in the implementer's own end-of-run review; TDD:
   red confirmed for the right reason, then fixed).

## Threading decision (spec 14 risk 7 input)

Plain background thread with interruptible sleep — NOT core.async. Reason:
the milestone freeze forbids new deps.edn entries and core.async is not
among the pinned deps; delta 11 sanctions the plain-thread loop as the
default. `stop!` is idempotent, bounded join (10 s), an in-flight pass
stops after its current batch (documented at most one 500-row batch); no
new pass starts after the signal. If M6/M7 wants core.async it is an
isolated swap inside cleanup.clj.

## Acceptance criteria (plan + run deltas)

- batch drain <=500 rows incl. >500 multi-batch case, blobs gone from
  directory listing, `NULL expires_at` survives, second pass deletes 0
  (`run-cleanup-once-drains-in-batches`); 404-before-job-run; PATCH
  semantics; jitter bounds; counters key set exact; start/stop without
  thread leak; delta-7 stress test; orphan sweep never takes an in-flight
  blob (grace 0) — all named tests, suite green.

## Divergences / notes for Cabal

- **Interval default 3600 s** (delta 2, your task order) instead of the
  plan text's 300 s — flagged as mandated.
- Orphan sweep grace default 300 s, parameterizable (delta 8); tests run
  at 0 grace against the in-flight registry gate (run-2 ruling).
- Run history: run1 killed at 26 min on a hung API call (contradiction
  delta7-vs-8 found pre-code; no code written); run2 reached green storage
  registry work then OOM-killed its own jolt via a test-spy recursion (var
  vs fn value) — fixed in-tree; run3 resumed, delivered commits 2-5, hit
  the 120-turn cap with only the final suite run + summary outstanding —
  those two items were completed by the orchestrator (suite run above).
- Review-fix rounds used: 0 of 2 (corrections were self-review; no
  orchestrator critique round was needed).

## Not done

- Nothing known outstanding in M4 scope. Worker is not auto-started from
  app.core (deliberate: M7 wiring); no metrics endpoint (M6); no manage
  routes (M6).
