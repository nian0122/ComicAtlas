CREATE TABLE lifecycle_cover_sources (
 entry_id TEXT PRIMARY KEY NOT NULL REFERENCES lifecycle_entries(id),
 source_json TEXT NOT NULL CHECK(json_valid(source_json) AND length(CAST(source_json AS BLOB))<=8192),
 source_key TEXT NOT NULL CHECK(length(source_key)=64)
);
CREATE TABLE lifecycle_cover_cache (
 entry_id TEXT PRIMARY KEY NOT NULL REFERENCES lifecycle_cover_sources(entry_id) ON DELETE CASCADE,
 source_key TEXT NOT NULL CHECK(length(source_key)=64),sha256 TEXT NOT NULL CHECK(length(sha256)=64),
 width INTEGER NOT NULL CHECK(width BETWEEN 1 AND 512),height INTEGER NOT NULL CHECK(height BETWEEN 1 AND 512),
 size INTEGER NOT NULL CHECK(size BETWEEN 1 AND 2097152),
 content BLOB NOT NULL CHECK(typeof(content)='blob' AND length(content)=size),created_at TEXT NOT NULL
);
CREATE INDEX lifecycle_cover_cache_age ON lifecycle_cover_cache(created_at,entry_id,size);
