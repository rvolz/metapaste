---
name: metapaste-administration
description: "Run metapaste deployments: config, keys, ops."
version: 0.1.0
author: Rainer (rvolz), Hermes Agent
license: MIT
platforms: [linux, macos]
metadata:
  hermes:
    tags: [metapaste, operations, deployment, backup]
    related_skills: [metapaste-usage]
---

# metapaste Administration Skill

Operate a metapaste v2 paste server: build and run the binary or container,
configure it, manage API keys, monitor health and metrics, run cleanup, and
back up / restore the data directory. Single binary, single node, SQLite (WAL)
plus filesystem blobs under `data_dir`. Not for day-to-day paste usage (→
`metapaste-usage`) and not for developing the codebase (repo `AGENTS.md`).
Full config table and ops notes: the repo's `README.md`; API shapes:
`docs/openapi.yaml` (frozen).

## When to Use

- Deploy, restart, or reconfigure a metapaste server (binary or container).
- Mint, list, revoke API keys; purge items; read stats or effective config.
- Monitor health/metrics, run an on-demand cleanup pass, back up or restore.
- Don't use for: everyday upload/fetch of pastes (→ `metapaste-usage`);
  changing code (milestones, `./scripts/test.sh`).

## Prerequisites

- Binary build: `./scripts/build.sh` (needs jolt v0.8.20 + threaded Chez
  Scheme 10.4.1) → self-contained `./metapaste`, no JVM. Container path:
  `docker build -t metapaste .` (multi-stage; the image ships only the binary).
- Admin token: `METAPASTE_ADMIN_TOKEN` — any string you choose. Unset disables
  every `/manage/v1` endpoint. Treat it like a password; never echo or log it.
- `curl`; `docker` only for the container path.

## How to Run

```python
terminal(command='./metapaste config check')            # effective config, redacted
terminal(command='METAPASTE_ADMIN_TOKEN=... ./metapaste serve')  # blocks; logs JSON lines
```

Admin API calls use the admin token as bearer:

```python
terminal(command='curl -sS -H "Authorization: Bearer $MET...EN" http://127.0.0.1:8080/manage/v1/stats')
```

## Quick Reference

```sh
./scripts/build.sh                       # build ./metapaste
./metapaste config check                 # effective config as redacted JSON
METAPASTE_ADMIN_TOKEN=... ./metapaste serve   # 127.0.0.1:8080, data in ./data
# container: uid 10001, read-only rootfs, one writable volume /data
docker build -t metapaste . && docker run -d --name metapaste --read-only \
  -v metapaste-data:/data -p 8080:8080 -e METAPASTE_ADMIN_TOKEN=... metapaste
./scripts/docker-selftest.sh             # proves the container contract (SKIP_BUILD=1 reuses image)
# management (admin token): stats, cleanup, keys, purge, config
curl -sS -H "$ADMIN" "$URL/manage/v1/stats"
curl -sS -X POST -H "$ADMIN" "$URL/manage/v1/cleanup"
curl -sS -H "$ADMIN" "$URL/manage/v1/keys"
curl -sS -X POST -H "$ADMIN" -H 'Content-Type: application/json' \
  -d '{"label":"laptop","scopes":"read,write"}' "$URL/manage/v1/keys"
curl -sS -X DELETE -H "$ADMIN" "$URL/manage/v1/keys/<key_id>"
curl -sS -X DELETE -H "$ADMIN" "$URL/manage/v1/items/<name>"   # admin purge
curl -sS -H "$ADMIN" "$URL/manage/v1/config"
# unauthenticated probes
curl -sS "$URL/healthz"; curl -sS "$URL/readyz"
curl -sS "http://127.0.0.1:8081/metrics"    # internal listener only
```

## Procedure

1. **Configure.** Env vars beat the EDN config file
   (`~/.config/metapaste/config.edn` or `METAPASTE_CONFIG_FILE`) beat
   defaults; every key is also `METAPASTE_<KEY>`. Set `data_dir`,
   `admin_token`, `bind_host`/`port` as needed. Completion: `config check`
   exits 0, prints redacted JSON, and shows no unknown-key warnings (invalid
   values fail startup fast).
2. **Start.** `serve` in foreground (or under your supervisor); container as
   above. Metrics listener defaults to `port` + 1, loopback. Completion:
   `GET /healthz` → 200 and `GET /readyz` → 200 (`503` = storage not writable
   or migrations not current).
3. **Key lifecycle.** `POST /manage/v1/keys` with `{"label": ..., "scopes":
   "read,write"}` (label required and non-blank; scopes `read`, `write`, or
   both; omitted = `read`-only). The `201` response carries the plaintext
   `mp_<keyid>_<secret>` exactly once — store it in the client's secret store
   immediately. Rotate: mint new key → switch clients → `DELETE
   /manage/v1/keys/<key_id>` (204 once, 404 on repeat). Completion:
   `GET /manage/v1/keys` lists `key_id`/label/scopes and never any secret.
4. **Monitor.** `GET /manage/v1/stats` → `items_live`, `items_expired`,
   `bytes_total`, `oldest_created`, `newest_created`, `api_keys_active`.
   `GET /metrics` on the internal listener (Prometheus text 0.0.4). Logs are
   JSON lines on stdout and must never contain item content or secrets.
5. **Cleanup.** A background worker purges expired items every
   `cleanup_interval_s` (3600 default). Force one pass:
   `POST /manage/v1/cleanup` → `{"deleted": N, ...}` (plus `orphans` and
   duration).
6. **Backup / restore.** Copy `data_dir` wholesale — SQLite db + `-wal` +
   `blobs/` — while running or stopped (WAL makes a file-level copy consistent
   for a cold restore). Restore: stop, replace the directory contents, start.
   Daily copy ⇒ RPO 24 h / RTO 1 h. Deleted and expired data is unrecoverable
   by design: hard delete, no tombstones, no undelete.
7. **Shutdown.** SIGTERM (so `docker stop` is clean): cleanup worker stops,
   in-flight requests drain (10 s bound), storage closes, exit 0.

## Pitfalls

- The key secret exists in exactly one response and only its sha256 is stored
  — a lost secret cannot be recovered, mint a new key. Never log either token.
- No `admin_token` ⇒ the whole `/manage/v1` surface is disabled (the CLI
  prints a warning); there is no other admin path.
- `GET /metrics` is NOT routed on the public listener. Publish `8081` only
  when it is actually scraped; otherwise keep it on loopback/host network.
- TLS terminates at your reverse proxy (Caddy/nginx/traefik); the app serves
  plain HTTP. Do not expose it directly to the internet. `path_prefix` must
  match the proxy subpath.
- Container discipline: non-root uid 10001, read-only root filesystem, one
  writable volume at `/data`; it binds `0.0.0.0` in-container. Prefer
  `./scripts/docker-selftest.sh` after image changes.
- CORS is disabled unless `cors_allow_origins` lists exact origins (env value
  comma-separated); wildcards unsupported, no allowlist ⇒ no CORS headers.
- Unknown config keys only warn; invalid values fail startup. Env always wins
  over the EDN file — check `config check` before debugging "ignored" edits.
- `dev_api_key` is redacted config history and grants nothing since M5.
- Per-key rate limits (`rate_limit_rps` 10 / `rate_limit_burst` 20 defaults):
  user-visible 429s mean you tune these or the client backs off — don't retry
  harder.
- `allow_never_expire` is `false` by default; users asking for immortal pastes
  need `ttl_seconds: 0` accepted, which requires flipping it deliberately.

## Verification

- `./metapaste config check` exits 0 and reflects the intended values with
  secrets redacted.
- Fresh start: `healthz` 200 and `readyz` 200; mint a throwaway key and
  round-trip one item end to end (create → fetch content → delete) as in
  `metapaste-usage`; revoke the throwaway key (204).
- Container path: `./scripts/docker-selftest.sh` exits 0.
- Backup drill: restore a copied `data_dir` on a scratch instance and read a
  known item back before trusting the backup.
