# metapaste v2 — agent conventions

1. Only touch files inside the repository working directory (your worktree).
2. Every dependency version-pinned. No new dependency without listing why in
   your final summary. Prefer stdlib / already-present deps.
3. Every milestone ships `clojure.test` tests and updates `scripts/test.sh`
   if a new test namespace appears. `./scripts/test.sh` must exit 0 when you
   finish. Run it yourself before finishing.
4. Never log item content or API secrets. Never build SQL from concatenated
   user input (parameterized statements only).
5. No stubs or TODOs left inside the milestone's scope. Stubs for future
   milestones are allowed only where the milestone prompt explicitly says so.
6. Keep handler/business code free of framework imports where the prompt
   asks for an interface boundary — swap cost must stay local.

Run `./scripts/test.sh` before finishing.

## Layout

- `src/app/core.clj` — entry namespace `app.core` (`-main`).
- `src/metapaste/` — config, logging, later storage/HTTP.
- `test/` — `clojure.test` namespaces mirroring `src/`.
- `scripts/run_tests.clj` — discovers `test/**/*_test.clj` automatically.
- `docs/openapi.yaml` — FROZEN API contract; do not invent endpoints beyond
  spec §5/§8. Sanctioned infra exception: `GET {path_prefix}/openapi.json`
  serves the frozen contract as JSON (`resources/openapi.json`, regenerated
  from the YAML only via `scripts/gen-openapi-json.py`) — keep this route.

## Stack notes (jolt v0.8.20)

- Build: `jolt build -m app.core -o metapaste` (wrapped by `scripts/build.sh`).
- `clojure.data.json` for JSON; `next.jdbc`/Jackson are dead on jolt — do not add.
- UTC timestamps everywhere (`java.time.Instant/now`).
- `thrown?` works only inside `clojure.test/is`; `with-err-str` does not exist.