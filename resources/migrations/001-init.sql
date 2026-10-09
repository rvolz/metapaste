-- 001: initial schema (spec §3.2). Forward-only, recorded in schema_migrations.
CREATE TABLE items (
  name TEXT PRIMARY KEY,
  description TEXT,
  mime_type TEXT NOT NULL,
  content_kind TEXT NOT NULL,
  size_bytes INTEGER NOT NULL,
  checksum_sha256 TEXT NOT NULL,
  storage_ref TEXT NOT NULL,
  ttl_seconds INTEGER,
  created_at TIMESTAMP NOT NULL,
  expires_at TIMESTAMP,
  owner_key_id TEXT
);

CREATE TABLE api_keys (
  key_id TEXT PRIMARY KEY,
  key_hash TEXT NOT NULL,
  label TEXT NOT NULL,
  scopes TEXT NOT NULL,
  created_at TIMESTAMP NOT NULL,
  revoked_at TIMESTAMP
);
