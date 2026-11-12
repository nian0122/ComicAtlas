CREATE TABLE upload_sessions (
 id TEXT PRIMARY KEY NOT NULL, comic_id TEXT NOT NULL, chapter_id TEXT NOT NULL, replace_media_id TEXT,
 status TEXT NOT NULL CHECK(status IN ('active','cancelled','expired')),
 total_bytes INTEGER NOT NULL CHECK(total_bytes BETWEEN 1 AND 107374182400),
 created_at TEXT NOT NULL,expires_at TEXT NOT NULL,
 request_hash TEXT NOT NULL CHECK(length(request_hash)=64),idempotency_key TEXT UNIQUE,
 previous_json TEXT CHECK(previous_json IS NULL OR (json_valid(previous_json) AND json_type(previous_json)='object')),
 cleaned INTEGER NOT NULL DEFAULT 0 CHECK(cleaned IN(0,1))
);
CREATE INDEX upload_sessions_expiry ON upload_sessions(expires_at,id) WHERE status='active';
CREATE INDEX upload_sessions_cleanup ON upload_sessions(id) WHERE status IN ('cancelled','expired') AND cleaned=0;
CREATE INDEX upload_sessions_chapter ON upload_sessions(chapter_id) WHERE status='active';
CREATE TABLE upload_files (
 session_id TEXT NOT NULL REFERENCES upload_sessions(id) ON DELETE CASCADE,
 id TEXT NOT NULL,position INTEGER NOT NULL CHECK(position BETWEEN 1 AND 10000),
 name TEXT NOT NULL,name_key TEXT NOT NULL,size INTEGER NOT NULL CHECK(size BETWEEN 1 AND 21474836480),
 sha256 TEXT NOT NULL CHECK(length(sha256)=64), received_bytes INTEGER NOT NULL DEFAULT 0 CHECK(received_bytes BETWEEN 0 AND size),
 PRIMARY KEY(session_id,id), UNIQUE(session_id,position),UNIQUE(session_id,name_key)
);
CREATE TABLE upload_parts (
 session_id TEXT NOT NULL,file_id TEXT NOT NULL,part INTEGER NOT NULL CHECK(part BETWEEN 0 AND 1279),
 size INTEGER NOT NULL CHECK(size BETWEEN 1 AND 16777216),sha256 TEXT NOT NULL CHECK(length(sha256)=64),
 PRIMARY KEY(session_id,file_id,part), FOREIGN KEY(session_id,file_id) REFERENCES upload_files(session_id,id) ON DELETE CASCADE
);
