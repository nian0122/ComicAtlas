CREATE TABLE tasks_next (
 id TEXT PRIMARY KEY NOT NULL, type TEXT NOT NULL CHECK(type IN ('directory_import','archive_import','image_lq','storage_refresh','hq_release','directory_scan','archive_export','video_transcode','media_upload')),
 source_path TEXT NOT NULL, title TEXT NOT NULL, comic_id TEXT NOT NULL,
 status TEXT NOT NULL CHECK(status IN ('queued','running','cancelling','cancelled','succeeded','failed')),
 stage TEXT NOT NULL, attempt INTEGER NOT NULL DEFAULT 0, total_count INTEGER, completed_count INTEGER NOT NULL DEFAULT 0,
 error_code TEXT, manifest TEXT, idempotency_key TEXT UNIQUE, request_hash TEXT NOT NULL,
 created_at TEXT NOT NULL, started_at TEXT, finished_at TEXT,
 title_explicit INTEGER NOT NULL DEFAULT 1 CHECK(title_explicit IN (0,1)),
 target_type TEXT NOT NULL DEFAULT '', target_id TEXT NOT NULL DEFAULT '', request_json TEXT,
 CHECK ((type IN ('directory_import','archive_import') AND source_path<>'' AND target_type='' AND target_id='' AND (request_json IS NULL OR (json_valid(request_json) AND json_type(request_json)='object')))
 OR (type IN ('image_lq','hq_release') AND source_path='' AND target_type IN ('comic','chapter') AND target_id<>'' AND request_json IS NOT NULL AND json_valid(request_json) AND json_type(request_json)='object')
 OR (type='storage_refresh' AND source_path='' AND comic_id='' AND target_type='storage' AND target_id='media' AND request_json='{}')
 OR (type='directory_scan' AND source_path='' AND comic_id='' AND target_type='import_root' AND target_id<>'' AND request_json IS NOT NULL AND json_valid(request_json) AND json_type(request_json)='object')
 OR (type='media_upload' AND source_path='' AND target_type='upload_session' AND comic_id<>'' AND target_id<>'' AND request_json='{}')
 OR (type='video_transcode' AND source_path='' AND target_type IN ('comic','chapter','media') AND target_id<>'' AND comic_id<>'' AND request_json='{}')
 OR (type='archive_export' AND source_path='' AND target_type='comic' AND comic_id=target_id AND comic_id<>'' AND request_json IS NOT NULL AND json_valid(request_json) AND json_type(request_json)='object'))
);
INSERT INTO tasks_next SELECT * FROM tasks;
DROP TABLE tasks;
ALTER TABLE tasks_next RENAME TO tasks;
CREATE INDEX tasks_queue ON tasks(status,created_at,id);
CREATE INDEX tasks_recent ON tasks(created_at DESC,id DESC);
CREATE INDEX tasks_type_recent ON tasks(type,created_at DESC,id DESC);
CREATE INDEX tasks_active_comic ON tasks(comic_id,type) WHERE status IN ('queued','running','cancelling');
CREATE UNIQUE INDEX tasks_active_source ON tasks(source_path) WHERE type IN ('directory_import','archive_import') AND status IN ('queued','running','cancelling');
CREATE UNIQUE INDEX tasks_active_target ON tasks(type,target_type,target_id) WHERE type IN ('image_lq','storage_refresh','hq_release','directory_scan','archive_export','video_transcode','media_upload') AND status IN ('queued','running','cancelling');

CREATE TABLE upload_sessions_next (
 id TEXT PRIMARY KEY NOT NULL, comic_id TEXT NOT NULL, chapter_id TEXT NOT NULL, replace_media_id TEXT,
 status TEXT NOT NULL CHECK(status IN ('active','cancelled','expired','submitted','activated','completed')),
 total_bytes INTEGER NOT NULL CHECK(total_bytes BETWEEN 1 AND 107374182400),
 created_at TEXT NOT NULL,expires_at TEXT NOT NULL,
 request_hash TEXT NOT NULL CHECK(length(request_hash)=64),idempotency_key TEXT UNIQUE,
 task_id TEXT UNIQUE REFERENCES tasks(id),
 previous_json TEXT CHECK(previous_json IS NULL OR (json_valid(previous_json) AND json_type(previous_json)='object')),
 cleaned INTEGER NOT NULL DEFAULT 0 CHECK(cleaned IN(0,1))
);
INSERT INTO upload_sessions_next(id,comic_id,chapter_id,replace_media_id,status,total_bytes,created_at,expires_at,request_hash,idempotency_key,previous_json,cleaned) SELECT id,comic_id,chapter_id,replace_media_id,status,total_bytes,created_at,expires_at,request_hash,idempotency_key,previous_json,cleaned FROM upload_sessions;
DROP TABLE upload_sessions;
ALTER TABLE upload_sessions_next RENAME TO upload_sessions;
CREATE INDEX upload_sessions_expiry ON upload_sessions(expires_at,id) WHERE status='active';
CREATE INDEX upload_sessions_cleanup ON upload_sessions(id) WHERE status IN ('cancelled','expired') AND cleaned=0;
CREATE INDEX upload_sessions_chapter ON upload_sessions(chapter_id) WHERE status IN ('active','submitted','activated');
CREATE INDEX upload_sessions_unfinished ON upload_sessions(comic_id,task_id) WHERE status IN ('submitted','activated');
ALTER TABLE upload_files ADD COLUMN media_id TEXT;
ALTER TABLE upload_files ADD COLUMN inspection_json TEXT CHECK(inspection_json IS NULL OR (json_valid(inspection_json) AND json_type(inspection_json)='object'));
ALTER TABLE upload_files ADD COLUMN publish_state TEXT NOT NULL DEFAULT 'pending' CHECK(publish_state IN ('pending','prepared','published'));
ALTER TABLE upload_files ADD COLUMN error_code TEXT;
CREATE TABLE upload_retired (
 task_id TEXT NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,kind TEXT NOT NULL CHECK(kind IN ('hq','lq')),
 path TEXT NOT NULL,sha256 TEXT NOT NULL,size INTEGER NOT NULL CHECK(size>=0),
 status TEXT NOT NULL DEFAULT 'pending' CHECK(status IN ('pending','moved','absent')),
 PRIMARY KEY(task_id,kind)
);
ALTER TABLE upload_sessions ADD COLUMN prior_json TEXT CHECK(prior_json IS NULL OR (json_valid(prior_json) AND json_type(prior_json)='object'));
ALTER TABLE upload_files ADD COLUMN target_path TEXT;
