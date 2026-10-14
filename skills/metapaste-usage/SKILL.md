---
name: metapaste-usage
description: "Use a metapaste server: upload, fetch, manage items."
version: 0.1.0
author: Rainer (rvolz), Hermes Agent
license: MIT
platforms: [linux, macos]
metadata:
  hermes:
    tags: [metapaste, api, pastes, curl]
    related_skills: [metapaste-administration]
---

# metapaste Usage Skill

Client-side operations against a running metapaste v2 paste server over its
HTTP API: create, read, list, update, and delete items (pastes). Every item
carries an explicit MIME type and a TTL. This skill does NOT cover deploying
the server, minting keys, or server config — that is
`metapaste-administration`. The frozen API contract is `docs/openapi.yaml` in
the metapaste repo; where this skill and the contract disagree, the contract
wins.

## When to Use

- Store a snippet, thought, or file on a metapaste server.
- Fetch item metadata or raw content by name; list items with filters.
- Update an item's description or TTL; delete an item you own.
- Don't use for: minting/revoking API keys, deployment, config, backup,
  metrics, purging other users' items (→ `metapaste-administration`);
  developing metapaste itself (repo `AGENTS.md`).

## Prerequisites

- Base URL of a running server, e.g. `http://127.0.0.1:8080`. If the server
  runs with `path_prefix` (e.g. `/mp`), prepend it to every path below.
- An API key secret `mp_<keyid>_<secret>` from the operator: `read` scope for
  GET, `write` scope for POST/PATCH/DELETE (typical keys carry `read,write`).
- `curl`. Keep the secret in a shell variable for the session; never echo it,
  never put it in chat, never commit it.

## How to Run

All calls go through the `terminal` tool. Export once per session, then reuse:

```sh
export METAPASTE_URL=http://127.0.0.1:8080
export METAPASTE_KEY='mp_...'        # secret from the operator, never printed
```

```python
terminal(command='curl -sS -H "Authorization: Bearer $METAPASTE_KEY" "$METAPASTE_URL/api/v1/items"')
```

## Quick Reference

```sh
# create text item (write scope)
curl -sS -H "Authorization: Bearer $METAPASTE_KEY" -H 'Content-Type: application/json' \
  -d '{"content":"print(1)","mime_type":"text/x-python","description":"snippet","ttl_seconds":86400}' \
  "$METAPASTE_URL/api/v1/items"
# create file item (multipart)
curl -sS -H "Authorization: Bearer $METAPASTE_KEY" \
  -F file=@photo.png -F mime_type=image/png "$METAPASTE_URL/api/v1/items"
# read
curl -sS -H "Authorization: Bearer $METAPASTE_KEY" "$METAPASTE_URL/api/v1/items/<name>"
curl -sS -H "Authorization: Bearer $METAPASTE_KEY" "$METAPASTE_URL/api/v1/items/<name>/content"
# list (cursor pagination)
curl -sS -H "Authorization: Bearer $METAPASTE_KEY" "$METAPASTE_URL/api/v1/items?limit=50&mime_prefix=text/"
# update description / ttl (content is immutable)
curl -sS -X PATCH -H "Authorization: Bearer $METAPASTE_KEY" -H 'Content-Type: application/json' \
  -d '{"description":"fixed","ttl_seconds":604800}' "$METAPASTE_URL/api/v1/items/<name>"
# delete (owner key only)
curl -sS -X DELETE -H "Authorization: Bearer $METAPASTE_KEY" "$METAPASTE_URL/api/v1/items/<name>"
```

## Procedure

1. **Create.** POST JSON (`content` required, valid UTF-8) or multipart
   (`file` part required). Completion: `201` with JSON `ItemMetadata`; save
   `name` (matches `^[a-z0-9]+$`) — the server generates it.
2. **Read.** GET `.../items/<name>` for metadata (includes `checksum_sha256`,
   `expires_at`), GET `.../items/<name>/content` for raw bytes with the stored
   `Content-Type`. Completion: `200`, content round-trips byte-identical.
3. **List.** GET `.../items` with `limit` (1–500, default 50), `cursor`,
   `mime_prefix`, `created_after`/`created_before` (ISO-8601),
   `include_expired`. Completion: every page consumed until `next_cursor` is
   `null`.
4. **Update.** PATCH `description` and/or `ttl_seconds` (at least one field).
   Completion: `200` with updated metadata.
5. **Delete.** DELETE `.../items/<name>` with the owning key. Completion:
   `204`; a follow-up GET returns `404`.

## Pitfalls

- Expired items 404 immediately and are indistinguishable from nonexistent
  ones — an apparent "loss" is usually expiry. Default TTL is 3 days.
- Content is immutable after create; only `description` (≤ 1024 chars) and
  `ttl_seconds` can change. Wrong bytes = create a new item.
- TTL bounds: `min_ttl_seconds` 60 to `max_ttl_seconds` 31536000; PATCH TTLs
  are re-clamped to bounds. `ttl_seconds: 0` (never expire) is accepted only
  when the server sets `allow_never_expire=true`.
- Write methods (POST/PATCH/DELETE) need `write` scope, GET needs `read` —
  `403` otherwise. Delete is owner-key-only; `403` for other keys' items.
- Errors are RFC 7807 `application/problem+json` (`type,title,status,detail`)
  — parse `status`, don't regex prose. `429` = per-key rate limit (token
  bucket, 10 rps / burst 20 by default): back off, don't retry in a loop.
- JSON create accepts UTF-8 text only; binary goes multipart. Multipart
  requires Content-Length (`411` otherwise) and fits `max_item_bytes`
  (`413` otherwise; 10 MiB default). Non-JSON content type on create → `415`.
- `description` max 1024 chars; MIME essence is stored lowercase
  (e.g. `text/x-python`).
- ETag is the SHA-256 of the content; send `If-None-Match` to get `304`
  instead of a full body.
- Send `X-Request-Id` on every call for correlation; the server echoes it or
  generates one on each response.

## Verification

- Create returns `201` with `name`, `url`, `content_url`, `size_bytes`;
  `sha256sum` of the local input equals `checksum_sha256` in the metadata.
- GET `/content` returns the exact bytes uploaded (compare hashes, not eyeballs).
- After DELETE the name 404s, and the item no longer appears in list output.
