CREATE TABLE tasks_next (
 id TEXT PRIMARY KEY NOT NULL, type TEXT NOT NULL CHECK(type IN ('directory_import','archive_import','image_lq','storage_refresh','hq_release')),
 source_path TEXT NOT NULL, title TEXT NOT NULL, comic_id TEXT NOT NULL,
 status TEXT NOT NULL CHECK(status IN ('queued','running','cancelling','cancelled','succeeded','failed')),
 stage TEXT NOT NULL, attempt INTEGER NOT NULL DEFAULT 0, total_count INTEGER, completed_count INTEGER NOT NULL DEFAULT 0,
 error_code TEXT, manifest TEXT, idempotency_key TEXT UNIQUE, request_hash TEXT NOT NULL,
 created_at TEXT NOT NULL, started_at TEXT, finished_at TEXT,
 title_explicit INTEGER NOT NULL DEFAULT 1 CHECK(title_explicit IN (0,1)),
 target_type TEXT NOT NULL DEFAULT '', target_id TEXT NOT NULL DEFAULT '', request_json TEXT,
 CHECK ((type IN ('directory_import','archive_import') AND source_path<>'' AND target_type='' AND target_id='' AND request_json IS NULL)
 OR (type IN ('image_lq','hq_release') AND source_path='' AND target_type IN ('comic','chapter') AND target_id<>'' AND request_json IS NOT NULL AND json_valid(request_json) AND json_type(request_json)='object')
 OR (type='storage_refresh' AND source_path='' AND comic_id='' AND target_type='storage' AND target_id='media' AND request_json='{}'))
);
INSERT INTO tasks_next SELECT * FROM tasks;
DROP TABLE tasks;
ALTER TABLE tasks_next RENAME TO tasks;
CREATE INDEX tasks_queue ON tasks(status,created_at,id);
CREATE INDEX tasks_recent ON tasks(created_at DESC,id DESC);
CREATE INDEX tasks_type_recent ON tasks(type,created_at DESC,id DESC);
CREATE UNIQUE INDEX tasks_active_source ON tasks(source_path) WHERE type IN ('directory_import','archive_import') AND status IN ('queued','running','cancelling');
CREATE UNIQUE INDEX tasks_active_target ON tasks(type,target_type,target_id) WHERE type IN ('image_lq','storage_refresh','hq_release') AND status IN ('queued','running','cancelling');

ALTER TABLE media ADD COLUMN hq_state TEXT NOT NULL DEFAULT 'available' CHECK(hq_state IN ('available','releasing','released'));
CREATE TABLE hq_release_items (
 task_id TEXT NOT NULL REFERENCES tasks(id) ON DELETE CASCADE, media_id TEXT NOT NULL, position INTEGER NOT NULL,
 source_path TEXT NOT NULL, source_sha256 TEXT NOT NULL, original_bytes INTEGER NOT NULL CHECK(original_bytes>0),
 lq_path TEXT NOT NULL, lq_sha256 TEXT NOT NULL, lq_bytes INTEGER NOT NULL CHECK(lq_bytes>0),
 status TEXT NOT NULL DEFAULT 'pending' CHECK(status IN ('pending','prepared','released','failed')),
 outcome TEXT, error_code TEXT, PRIMARY KEY(task_id,media_id), UNIQUE(task_id,position),
 CHECK((status='released' AND outcome IS NOT NULL AND outcome IN ('removed','already_absent') AND error_code IS NULL) OR (status<>'released' AND outcome IS NULL))
);
