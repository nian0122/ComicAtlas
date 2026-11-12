-- 使用既有启动期任务表重建协议；原任务及外键事实保持不变。
CREATE TABLE tasks_next (
 id TEXT PRIMARY KEY NOT NULL, type TEXT NOT NULL CHECK(type IN ('directory_import','archive_import','image_lq','storage_refresh','hq_release','directory_scan','archive_export','video_transcode','media_upload','metadata_refresh','lifecycle','lifecycle_audit','metadata_sync','metadata_batch','command_batch','directory_move','directory_preflight','disk_recovery')),
 source_path TEXT NOT NULL, title TEXT NOT NULL, comic_id TEXT NOT NULL,
 status TEXT NOT NULL CHECK(status IN ('queued','running','cancelling','cancelled','succeeded','failed')),
 stage TEXT NOT NULL, attempt INTEGER NOT NULL DEFAULT 0, total_count INTEGER, completed_count INTEGER NOT NULL DEFAULT 0,
 error_code TEXT, manifest TEXT, idempotency_key TEXT UNIQUE, request_hash TEXT NOT NULL,
 created_at TEXT NOT NULL, started_at TEXT, finished_at TEXT,
 title_explicit INTEGER NOT NULL DEFAULT 1 CHECK(title_explicit IN (0,1)),
 target_type TEXT NOT NULL DEFAULT '', target_id TEXT NOT NULL DEFAULT '', request_json TEXT,
 CHECK ((type IN ('directory_import','archive_import') AND source_path<>'' AND target_type='' AND target_id='' AND (request_json IS NULL OR (json_valid(request_json) AND json_type(request_json)='object')))
 OR (type IN ('image_lq','hq_release') AND source_path='' AND target_type IN ('comic','chapter') AND target_id<>'' AND request_json IS NOT NULL AND json_valid(request_json) AND json_type(request_json)='object')
 OR (type IN('storage_refresh','disk_recovery') AND source_path='' AND comic_id='' AND target_type='storage' AND target_id='media' AND request_json='{}')
 OR (type='directory_scan' AND source_path='' AND comic_id='' AND target_type='import_root' AND target_id<>'' AND request_json IS NOT NULL AND json_valid(request_json) AND json_type(request_json)='object')
 OR (type='lifecycle_audit' AND source_path='' AND target_type='trash_entry' AND comic_id<>'' AND target_id<>'' AND request_json='{}')
 OR (type='lifecycle' AND source_path='' AND target_type IN ('comic','chapter','media') AND comic_id<>'' AND target_id<>'' AND request_json IS NOT NULL AND json_valid(request_json) AND json_type(request_json)='object')
 OR (type='metadata_refresh' AND source_path='' AND target_type='comic' AND comic_id=target_id AND comic_id<>'' AND request_json='{}')
 OR (type='metadata_sync' AND source_path='' AND target_type='comic' AND comic_id=target_id AND comic_id<>'' AND request_json='{}')
 OR (type='metadata_batch' AND source_path='' AND comic_id='' AND target_type='batch_preview' AND target_id<>'' AND request_json='{}')
 OR (type='command_batch' AND source_path='' AND comic_id='' AND target_type IN('image_lq','metadata_refresh','video_transcode','hq_release','comic_trash','chapter_trash','media_trash','trash_restore','trash_purge') AND target_id<>'' AND request_json='{}')
 OR (type='media_upload' AND source_path='' AND target_type='upload_session' AND comic_id<>'' AND target_id<>'' AND request_json='{}')
 OR (type='video_transcode' AND source_path='' AND target_type IN ('comic','chapter','media') AND target_id<>'' AND comic_id<>'' AND request_json='{}')
 OR (type IN('directory_move','directory_preflight') AND source_path='' AND comic_id='' AND target_type='directory_preview' AND target_id<>'' AND request_json='{}')
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
CREATE UNIQUE INDEX tasks_active_target ON tasks(type,target_type,target_id) WHERE type IN ('image_lq','storage_refresh','hq_release','directory_scan','archive_export','video_transcode','media_upload','metadata_refresh','lifecycle','lifecycle_audit','metadata_sync','metadata_batch','command_batch','directory_move','directory_preflight','disk_recovery') AND status IN ('queued','running','cancelling');

CREATE TABLE recovery_runs (
 task_id TEXT PRIMARY KEY NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
 sealed INTEGER NOT NULL DEFAULT 0 CHECK(sealed IN(0,1)),
 candidate_count INTEGER NOT NULL DEFAULT 0 CHECK(candidate_count BETWEEN 0 AND 100000),
 ignored_count INTEGER NOT NULL DEFAULT 0 CHECK(ignored_count BETWEEN 0 AND 200000)
);
CREATE TABLE recovery_candidates (
 task_id TEXT NOT NULL REFERENCES recovery_runs(task_id) ON DELETE CASCADE,
 comic_id TEXT NOT NULL CHECK(length(comic_id) BETWEEN 1 AND 128),
 PRIMARY KEY(task_id,comic_id)
);
CREATE TRIGGER recovery_run_identity BEFORE UPDATE OF task_id ON recovery_runs
BEGIN SELECT RAISE(ABORT,'恢复任务身份已冻结'); END;
CREATE TRIGGER recovery_run_sealed BEFORE UPDATE ON recovery_runs
WHEN OLD.sealed=1 AND (NEW.sealed<>1 OR NEW.candidate_count<>OLD.candidate_count OR NEW.ignored_count<>OLD.ignored_count)
BEGIN SELECT RAISE(ABORT,'恢复候选范围已冻结'); END;
CREATE TRIGGER recovery_candidate_insert BEFORE INSERT ON recovery_candidates
WHEN (SELECT sealed FROM recovery_runs WHERE task_id=NEW.task_id)=1
BEGIN SELECT RAISE(ABORT,'恢复候选范围已冻结'); END;
CREATE TRIGGER recovery_candidate_update BEFORE UPDATE ON recovery_candidates
BEGIN SELECT RAISE(ABORT,'恢复候选身份已冻结'); END;
CREATE TRIGGER recovery_candidate_delete BEFORE DELETE ON recovery_candidates
WHEN (SELECT sealed FROM recovery_runs WHERE task_id=OLD.task_id)=1
BEGIN SELECT RAISE(ABORT,'恢复候选范围已冻结'); END;
