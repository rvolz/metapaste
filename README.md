# metapaste v2

A small paste server for thoughts, snippets, and files. Every item carries an
explicit MIME type and is retrievable as typed content, with a TTL (default 3
days), API-key access control, a background cleanup job, and built-in
observability (Prometheus metrics, JSON-lines logs, health endpoints).

Single binary, single node, SQLite (WAL) + filesystem blobs. Written in
Clojure on [jolt](https://github.com/jolt-lang/jolt) (Clojure on threaded Chez
Scheme); `jolt build` produces a self-contained executable with no JVM.

API contract: [`docs/openapi.yaml`](docs/openapi.yaml) (frozen — the summary
below points at it for the authoritative shapes). The server publishes the
same document as OpenAPI 3 JSON at `GET {path_prefix}/openapi.json` (public,
no auth; `resources/openapi.json` is generated from the YAML by
`scripts/gen-openapi-json.py` and baked into the binary).

## Quickstart — binary

```sh
# build (needs jolt v0.8.20 + threaded Chez 10.4.1 on PATH)
./scripts/build.sh                      # -> ./metapaste

# run with the defaults (binds 127.0.0.1:8080, data in ./data)
METAPASTE_ADMIN_TOKEN=change-me ./metapaste serve
```

Create a key, then upload and read a paste:

```sh
ADMIN="Authorization: Bearer change-me"

# mint an API key (the secret is returned exactly once)
curl -s -H "$ADMIN" -H 'Content-Type: application/json' \
  -d '{"label":"laptop","scopes":"read,write"}' \
  http://127.0.0.1:8080/manage/v1/keys
# -> {"key_id":"...","secret":"mp_...", ...}

KEY="Authorization: Bearer mp_..."       # the secret from the response

# upload a text paste
curl -s -H "$KEY" -H 'Content-Type: application/json' \
  -d '{"content":"print(1)","mime_type":"text/x-python","description":"snippet"}' \
  http://127.0.0.1:8080/api/v1/items
# -> 201 {"name":"k3v9qx2m4pta","url":"/api/v1/items/k3v9qx2m4pta", ...}

# read metadata and typed content back
curl -s -H "$KEY" http://127.0.0.1:8080/api/v1/items/k3v9qx2m4pta
curl -s -H "$KEY" http://127.0.0.1:8080/api/v1/items/k3v9qx2m4pta/content

# health + metrics
curl -s http://127.0.0.1:8080/healthz
curl -s http://127.0.0.1:8081/metrics    # internal listener, loopback default
```

Files upload as `multipart/form-data` instead of JSON:

```sh
curl -s -H "$KEY" -F file=@photo.png -F mime_type=image/png \
  http://127.0.0.1:8080/api/v1/items
```

## Quickstart — container

```sh
docker build -t metapaste .
docker volume create metapaste-data
docker run -d --name metapaste \
  --read-only -v metapaste-data:/data \
  -p 8080:8080 -p 8081:8081 \
  -e METAPASTE_ADMIN_TOKEN=change-me \
  metapaste
```

The image runs as uid 10001 with a read-only root filesystem; `/data` (the
only writable path, a `VOLUME`) holds the SQLite database and blobs. It binds
`0.0.0.0` in-container (`METAPASTE_BIND_HOST`), and ships NO jolt/Chez on
PATH — only the self-contained binary built with `jolt build --closed-world`.
Publish `8081` (metrics) only when you mean to expose it. A repeatable
check of all of the above is `./scripts/docker-selftest.sh` (needs docker;
`SKIP_BUILD=1` reuses an existing image).

## Configuration

Env vars win over the config file, file keys win over defaults. The optional
EDN config file lives at `~/.config/metapaste/config.edn`, or is pointed at by
`METAPASTE_CONFIG_FILE`. Every key is also an env var `METAPASTE_<KEY>` in
upper-case. `metapaste config check` prints the effective config with secrets
redacted.

| Key (env `METAPASTE_<KEY>`)          | Default            | Notes |
|--------------------------------------|--------------------|-------|
| `data_dir`                           | `./data`           | SQLite db + `blobs/`; the container sets `/data` |
| `port`                               | `8080`             | API listener, `0` = ephemeral |
| `bind_host`                          | `127.0.0.1`        | listen address; container sets `0.0.0.0` |
| `metrics_port`                       | `port` + 1         | internal metrics listener, loopback by default |
| `path_prefix`                        | `""`               | e.g. `/mp` for subpath hosting; `""` = root |
| `admin_token`                        | (none)             | management API bearer token; unset disables admin endpoints |
| `default_ttl_seconds`                | `259200` (3 days)  | per-upload override allowed within min/max |
| `min_ttl_seconds`                    | `60`               | floor for per-item TTLs |
| `max_ttl_seconds`                    | `31536000` (365 d) | ceiling for per-item TTLs |
| `allow_never_expire`                 | `false`            | `ttl_seconds: 0` accepted only when true |
| `max_item_bytes`                     | `10485760` (10 MiB)| hard ceiling 100 MiB, rejected above it |
| `rate_limit_rps`                     | `10`               | per-key token bucket sustained rate |
| `rate_limit_burst`                   | `20`               | per-key bucket capacity |
| `cleanup_interval_s`                 | `3600`             | background cleanup worker period |
| `cors_allow_origins`                 | `[]` (disabled)    | exact-origin allowlist; env value is comma-separated |
| `dev_api_key`                        | `mp_dev_devkey`    | dev-era key material (redacted; grants nothing since M5) |

`METAPASTE_CONFIG_FILE` (path to the EDN file) is consumed by the loader
outside the key schema. Unknown keys warn; invalid values fail fast at
startup.

CORS (Q55): disabled unless `cors_allow_origins` lists origins; preflight
`OPTIONS` is answered (204) only for allowlisted origins, and only allowlisted
origins receive `Access-Control-Allow-Origin` on responses. Wildcards are not
supported.

## API summary

Full definitions in [`docs/openapi.yaml`](docs/openapi.yaml).

- **Items** (Bearer API key): `POST /api/v1/items` (JSON or multipart),
  `GET /api/v1/items` (cursor list), `GET|PATCH|DELETE /api/v1/items/{name}`,
  `GET /api/v1/items/{name}/content`. Errors are RFC 7807
  `application/problem+json`. Expired items 404 immediately, indistinguishable
  from nonexistent ones.
- **Health** (no auth): `GET /healthz` (liveness), `GET /readyz` (storage
  writable + migrations current, 503 when not).
- **Management** (admin token): `GET /manage/v1/stats`,
  `POST /manage/v1/cleanup`, `GET|POST /manage/v1/keys`,
  `DELETE /manage/v1/keys/{key_id}`, `DELETE /manage/v1/items/{name}`,
  `GET /manage/v1/config` (secrets redacted).
- **Metrics**: `GET /metrics` on the separate internal listener
  (Prometheus text 0.0.4), not routed on the public listener.

## Operations

- **Backup** = copy the data directory (SQLite db + WAL + `blobs/`) while the
  server runs or stopped; WAL makes a file-level copy consistent enough for a
  cold restore. RPO 24 h / RTO 1 h assumed (daily copy) — correct if wrong.
- **Expired/deleted data is unrecoverable by design**: hard delete of
  metadata + blob, no tombstones, no undelete. Only data that has not yet
  expired or been deleted exists to back up.
- **TLS** terminates at the reverse proxy (Caddy/nginx/traefik); the app
  serves plain HTTP on an internal port. Do not expose the app directly.
- **Container layout**: one writable volume at `/data`
  (`METAPASTE_DATA_DIR`); bind-mount the same path for bare-binary
  deployments if you want the same discipline. The metrics port is
  loopback-only outside containers — keep it unpublished unless scraped from
  the host network.
- **Graceful shutdown**: SIGTERM stops the cleanup worker, drains in-flight
  requests (10 s bound), closes storage, exits 0. `docker stop` therefore
  ends clean (verified by `scripts/docker-selftest.sh`).

## Development

```sh
./scripts/test.sh      # every clojure.test namespace under test/
```

REPL-driven development: one live `jolt repl`; see
https://jolt-lang.net/docs/repl-driven-development.html. Load smoke numbers
(measured, not assumed) are in [`docs/load-smoke.md`](docs/load-smoke.md).
