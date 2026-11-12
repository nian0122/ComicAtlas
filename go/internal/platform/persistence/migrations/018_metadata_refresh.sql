CREATE TABLE tasks_next (
 id TEXT PRIMARY KEY NOT NULL, type TEXT NOT NULL CHECK(type IN ('directory_import','archive_import','image_lq','storage_refresh','hq_release','directory_scan','archive_export','video_transcode','media_upload','metadata_refresh')),
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
 OR (type='metadata_refresh' AND source_path='' AND target_type='comic' AND comic_id=target_id AND comic_id<>'' AND request_json='{}')
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
CREATE UNIQUE INDEX tasks_active_target ON tasks(type,target_type,target_id) WHERE type IN ('image_lq','storage_refresh','hq_release','directory_scan','archive_export','video_transcode','media_upload','metadata_refresh') AND status IN ('queued','running','cancelling');

CREATE TABLE metadata_refresh_runs (
 task_id TEXT PRIMARY KEY NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
 comic_id TEXT NOT NULL,baseline_json TEXT NOT NULL CHECK(json_valid(baseline_json)),
 next_json TEXT CHECK(next_json IS NULL OR json_valid(next_json)),
 applied INTEGER NOT NULL DEFAULT 0 CHECK(applied IN(0,1)),
 completed INTEGER NOT NULL DEFAULT 0 CHECK(completed IN(0,1)),sidecar_sha TEXT,observed_at TEXT
);
CREATE INDEX metadata_refresh_unfinished ON metadata_refresh_runs(comic_id,task_id) WHERE applied=1 AND completed=0;
CREATE TABLE metadata_refresh_items (
 task_id TEXT NOT NULL REFERENCES metadata_refresh_runs(task_id) ON DELETE CASCADE,
 position INTEGER NOT NULL,media_id TEXT,chapter_id TEXT,file_name TEXT NOT NULL,status TEXT NOT NULL,error_code TEXT,
 item_json TEXT NOT NULL CHECK(json_valid(item_json)),PRIMARY KEY(task_id,position)
);
CREATE TABLE media_observations (
 media_id TEXT PRIMARY KEY NOT NULL REFERENCES media(id) ON DELETE CASCADE,
 task_id TEXT NOT NULL REFERENCES tasks(id),source_sha256 TEXT NOT NULL,status TEXT NOT NULL CHECK(status IN('present','changed','missing','released','invalid'))
);