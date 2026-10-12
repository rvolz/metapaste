# M5 — auth — implementation report

Date: 2026-10-12 (UTC)
Branch: `m5` (worktree `workspace/metapaste-v2/metapaste-v2-wt/m5`, base = M3
merge f1ff7c5; M4 is NOT merged and was not touched)
Implementer: dirge (untrusted lane, `--session metapaste-m5`), orchestrated by
dev. Workflow: REPL-driven throughout (live `jolt -M:test repl` inner loop;
`./scripts/test.sh` for periodic + final verification only; `jolt build` never
run — shipping-only).

## Result: GREEN, NOT MERGED — awaiting Cabal acceptance

`./scripts/test.sh` exit 0, verified by the orchestrator's own run (not just
dirge's claim): **72 tests / 1469 assertions / 0 failures / 0 errors**
(17 new auth tests / 150 assertions on top of M3's 55 tests).

## Delivered

- `src/metapaste/auth.clj` — key service: `create-key!` (returns
  `{:key_id :secret ...}`; `:secret` is the full plaintext `mp_<keyid>_<secret>`
  key, the ONLY place it exists), `revoke-key!` (stamps `revoked_at` -> 401),
  `authenticate`/`check-bearer` (distinct 401 details: missing / malformed /
  unknown / revoked), `scope-allows?` (read/write, write implies read),
  `admin?` (predicate, defaults false — M6 wires `:admin true`),
  `admin-token-ok?` (constant-time admin-token compare), `may-modify?`
  (owner check for PATCH/DELETE).
- `src/metapaste/ratelimit.clj` — per-key token bucket, defaults 10 rps /
  burst 20 from config (`rate_limit_rps`/`rate_limit_burst`, M1 keys),
  lazy-created buckets, idle eviction > 10 min, locking against double-spend.
  Rejection -> 429 problem+json with `Retry-After: 1`.
- `src/metapaste/storage.clj` — `api_keys` access (`create-api-key!`,
  `get-api-key`, `revoke-api-key!`), parameterized SQL only. Schema itself is
  unchanged from M2 (`001-init.sql` already had the spec §3.2 `api_keys` DDL);
  "key storage schema" deliverable = confirmed present + now exercised.
- `src/metapaste/api.clj` — the M3 `authed?` dev-key stub replaced by the
  real chain in `dispatch`: 401 (bearer) -> 403 (scope) -> 429 (rate limit)
  -> ownership gate on PATCH/DELETE (non-owner 403; unknown/expired stays
  404; admin bypass via `may-modify?`). Requests carry `:key-id`/`:scopes`;
  create records `owner_key_id`. The `:dev_api_key` config key stays (M1
  config tests pin it) but grants nothing anymore.
- Tests (`test/metapaste/auth_test.clj`, 17 tests): hash format (key_hash =
  64 lowercase hex == sha256(secret part), roundtrip), no-plaintext-secret-in-DB
  (raw `SELECT * FROM api_keys` + `sqlite_master` schema dump, secret appears
  nowhere), wrong-secret-same-length fails, revoked -> nil/401, 401 variants,
  scope matrix, ownership (owner 200/204, non-owner 403, unknown 404),
  burst-20-then-429 + refill recovery (short configurable params), idle
  eviction, secret-leak grep over captured logs (full key, secret part, and
  key_hash all absent). Integration fixture now creates a real key via
  `create-key!` with rate-limit headroom.

## Crypto surface (RQ12 checkpoint, memo GO)

- SHA-256 at rest: `java.security.MessageDigest` **via the jolt-lang/crypto
  provider** (OpenSSL FFI; the pinned `jolt-crypto` 44da69ba...) — reused
  `metapaste.storage/sha256-hex`. NOT stdlib (it isn't one on jolt), NOT a
  hand-rolled FFI binding.
- Secret generation: `java.security.SecureRandom` (same provider =
  OpenSSL `RAND_bytes`), 32 bytes -> unpadded base64url via
  `java.util.Base64/getUrlEncoder` (43 chars). **No `/dev/urandom` FFI, no
  `data.codec`** (stdlib encoder sufficed).
- Constant-time compare: `crypto.equality/eq?` everywhere (key_hash verify +
  admin token) — `crypto-equality/crypto-equality {:mvn/version "1.0.1"}`,
  the one new pinned dep (mandated by RQ12/Cabal). Review grep: zero `=` on
  secrets; the only `=` on key material is `key_id` vs `owner_key_id` (public
  identifiers, per spec Q17).

## Review checks (orchestrator, executed)

- Diff reviewed file-by-file: no scope creep, no `/manage/v1` routes (M6),
  no in-scope TODOs, deps pinned, SQL parameterized, no secrets logged.
- Secret grep over the full acceptance test log: zero `mp_...` tokens, no
  key_hash leak.
- 2 continuation runs used (run 1 hit the 80-turn cap mid-edit; run 2, same
  session, finished). Review-fix rounds used: 0 of 2.

## Not done (explicitly out of scope)

- `/manage/v1` routes incl. key CRUD (M6) — helpers (`create-key!`,
  `revoke-key!`, `admin?`, `admin-token-ok?`) are delivered and tested.
- Admin middleware wiring (M6) — `admin?` seam defaults to false.
- Merge to main + tag `m5` — awaiting Cabal acceptance (per M3 precedent).
- Note for M6: dirge's internal issue board has 4 stale items (drg-76c6,
  drg-238f, drg-2013, drg-1820, M2/M3-era) it could not close (tool bug);
  cosmetic, no code impact.
