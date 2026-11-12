CREATE TABLE tasks_next (
 id TEXT PRIMARY KEY NOT NULL, type TEXT NOT NULL CHECK(type IN ('directory_import','archive_import','image_lq','storage_refresh','hq_release','directory_scan','archive_export','video_transcode','media_upload','metadata_refresh','lifecycle','lifecycle_audit','metadata_sync','metadata_batch','command_batch','directory_move')),
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
 OR (type='lifecycle_audit' AND source_path='' AND target_type='trash_entry' AND comic_id<>'' AND target_id<>'' AND request_json='{}')
 OR (type='lifecycle' AND source_path='' AND target_type IN ('comic','chapter','media') AND comic_id<>'' AND target_id<>'' AND request_json IS NOT NULL AND json_valid(request_json) AND json_type(request_json)='object')
 OR (type='metadata_refresh' AND source_path='' AND target_type='comic' AND comic_id=target_id AND comic_id<>'' AND request_json='{}')
 OR (type='metadata_sync' AND source_path='' AND target_type='comic' AND comic_id=target_id AND comic_id<>'' AND request_json='{}')
 OR (type='metadata_batch' AND source_path='' AND comic_id='' AND target_type='batch_preview' AND target_id<>'' AND request_json='{}')
 OR (type='command_batch' AND source_path='' AND comic_id='' AND target_type IN('image_lq','metadata_refresh','video_transcode','hq_release','comic_trash','chapter_trash','media_trash','trash_restore','trash_purge') AND target_id<>'' AND request_json='{}')
 OR (type='media_upload' AND source_path='' AND target_type='upload_session' AND comic_id<>'' AND target_id<>'' AND request_json='{}')
 OR (type='video_transcode' AND source_path='' AND target_type IN ('comic','chapter','media') AND target_id<>'' AND comic_id<>'' AND request_json='{}')
 OR (type='directory_move' AND source_path='' AND comic_id='' AND target_type='directory_preview' AND target_id<>'' AND request_json='{}')
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
CREATE UNIQUE INDEX tasks_active_target ON tasks(type,target_type,target_id) WHERE type IN ('image_lq','storage_refresh','hq_release','directory_scan','archive_export','video_transcode','media_upload','metadata_refresh','lifecycle','lifecycle_audit','metadata_sync','metadata_batch','command_batch','directory_move') AND status IN ('queued','running','cancelling');

CREATE TABLE directory_move_previews (
 id TEXT PRIMARY KEY NOT NULL,
 token_hash TEXT NOT NULL CHECK(length(token_hash)=64),
 plan_data TEXT NOT NULL CHECK(length(CAST(plan_data AS BLOB)) BETWEEN 1 AND 67108864),
 plan_sha TEXT NOT NULL CHECK(length(plan_sha)=64),
 created_at TEXT NOT NULL, expires_at TEXT NOT NULL CHECK(expires_at>created_at),
 task_id TEXT UNIQUE REFERENCES tasks(id)
);
CREATE INDEX directory_move_preview_expiry ON directory_move_previews(expires_at) WHERE task_id IS NULL;
CREATE TRIGGER directory_move_preview_immutable BEFORE UPDATE OF id,token_hash,plan_data,plan_sha,created_at,expires_at ON directory_move_previews
BEGIN SELECT RAISE(ABORT,'移出预览已冻结'); END;
CREATE TRIGGER directory_move_preview_claim_once BEFORE UPDATE OF task_id ON directory_move_previews WHEN OLD.task_id IS NOT NULL
BEGIN SELECT RAISE(ABORT,'移出预览已受理'); END;
CREATE TABLE directory_move_runs (
 task_id TEXT PRIMARY KEY NOT NULL REFERENCES tasks(id),
 preview_id TEXT NOT NULL UNIQUE REFERENCES directory_move_previews(id),
 seal_json TEXT CHECK(seal_json IS NULL OR (length(CAST(seal_json AS BLOB))<=1024 AND json_valid(seal_json) AND json_type(seal_json)='object')),
 completed INTEGER NOT NULL DEFAULT 0 CHECK(completed IN(0,1))
);
CREATE TRIGGER directory_move_run_immutable BEFORE UPDATE OF task_id,preview_id ON directory_move_runs
BEGIN SELECT RAISE(ABORT,'移出任务身份已冻结'); END;
CREATE TRIGGER directory_move_seal_immutable BEFORE UPDATE OF seal_json ON directory_move_runs WHEN OLD.seal_json IS NOT NULL AND OLD.seal_json IS NOT NEW.seal_json
BEGIN SELECT RAISE(ABORT,'移出测量已冻结'); END;
CREATE TRIGGER directory_move_completion_monotonic BEFORE UPDATE OF completed ON directory_move_runs WHEN OLD.completed=1 AND NEW.completed<>1
BEGIN SELECT RAISE(ABORT,'移出已完成'); END;
CREATE TABLE directory_move_members (
 task_id TEXT NOT NULL REFERENCES directory_move_runs(task_id),
 position INTEGER NOT NULL CHECK(position BETWEEN 1 AND 10000),
 comic_id TEXT NOT NULL,
 inventory_sha TEXT NOT NULL CHECK(length(inventory_sha)=64),
 detached INTEGER NOT NULL DEFAULT 0 CHECK(detached IN(0,1)),
 PRIMARY KEY(task_id,position), UNIQUE(task_id,comic_id)
);
CREATE UNIQUE INDEX directory_move_member_lock ON directory_move_members(comic_id) WHERE detached=0;
CREATE TRIGGER directory_move_member_immutable BEFORE UPDATE OF task_id,position,comic_id,inventory_sha ON directory_move_members
BEGIN SELECT RAISE(ABORT,'移出范围已冻结'); END;
CREATE TRIGGER directory_move_detachment_monotonic BEFORE UPDATE OF detached ON directory_move_members WHEN OLD.detached=1 AND NEW.detached<>1
BEGIN SELECT RAISE(ABORT,'对象已脱管'); END;
CREATE TABLE directory_move_term_locks (
 task_id TEXT NOT NULL REFERENCES directory_move_runs(task_id),
 kind TEXT NOT NULL CHECK(kind IN('categories','tags')),
 term_id TEXT NOT NULL,
 PRIMARY KEY(task_id,kind,term_id)
);
CREATE INDEX directory_move_term_scope ON directory_move_term_locks(kind,term_id,task_id);
