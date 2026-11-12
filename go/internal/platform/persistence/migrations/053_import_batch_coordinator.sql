-- 导入批次作为可恢复父任务运行；子导入仍受全局活动任务上限约束。
CREATE TABLE tasks_next (
 id TEXT PRIMARY KEY NOT NULL, type TEXT NOT NULL CHECK(type IN ('directory_import','archive_import','image_lq','storage_refresh','hq_release','directory_scan','archive_export','video_transcode','media_upload','metadata_refresh','lifecycle','lifecycle_audit','metadata_sync','metadata_batch','command_batch','directory_move','directory_preflight','disk_recovery','recovery_repair','ai_analysis','import_batch')),
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
 OR (type='ai_analysis' AND source_path='' AND target_type='comic' AND comic_id=target_id AND comic_id<>'' AND request_json IS NOT NULL AND json_valid(request_json) AND json_type(request_json)='object')
 OR (type='recovery_repair' AND source_path='' AND target_type='comic' AND comic_id=target_id AND comic_id<>'' AND request_json IS NOT NULL AND json_valid(request_json) AND json_type(request_json)='object' AND json_type(request_json,'$.version') IS 'integer' AND json_extract(request_json,'$.version')>0 AND json_type(request_json,'$.structureVersion') IS 'integer' AND json_extract(request_json,'$.structureVersion')>0)
 OR (type='archive_export' AND source_path='' AND target_type='comic' AND comic_id=target_id AND comic_id<>'' AND request_json IS NOT NULL AND json_valid(request_json) AND json_type(request_json)='object')
 OR (type='import_batch' AND source_path='' AND comic_id='' AND target_type='import_batch' AND target_id<>'' AND request_json='{}'))
);
INSERT INTO tasks_next SELECT * FROM tasks;
DROP TRIGGER recovery_result_insert;
DROP TRIGGER recovery_result_update;
DROP TRIGGER ai_result_guard;
DROP TABLE tasks;
ALTER TABLE tasks_next RENAME TO tasks;
CREATE TRIGGER recovery_result_insert BEFORE INSERT ON recovery_results
WHEN NOT EXISTS(SELECT 1 FROM recovery_runs r JOIN tasks t ON t.id=r.task_id WHERE r.task_id=NEW.task_id AND r.sealed=1 AND t.type='disk_recovery' AND t.status='running' AND t.attempt=NEW.task_attempt)
BEGIN SELECT RAISE(ABORT,'恢复执行已失效'); END;
CREATE TRIGGER recovery_result_update BEFORE UPDATE ON recovery_results
WHEN OLD.status<>'error' OR NEW.task_attempt<=OLD.task_attempt OR NEW.try_count<>OLD.try_count+1
 OR NOT EXISTS(SELECT 1 FROM recovery_runs r JOIN tasks t ON t.id=r.task_id WHERE r.task_id=NEW.task_id AND r.sealed=1 AND t.type='disk_recovery' AND t.status='running' AND t.attempt=NEW.task_attempt)
BEGIN SELECT RAISE(ABORT,'恢复结果不能被覆盖'); END;
CREATE TRIGGER ai_result_guard BEFORE UPDATE OF result_request_json,candidate_json,task_attempt,completed_at ON ai_runs
WHEN OLD.candidate_json IS NOT NULL OR NEW.candidate_json IS NULL OR NOT EXISTS(SELECT 1 FROM tasks t WHERE t.id=NEW.task_id AND t.type='ai_analysis' AND t.status='running' AND t.attempt=NEW.task_attempt)
BEGIN SELECT RAISE(ABORT,'AI 候选提交已失效'); END;
CREATE INDEX tasks_queue ON tasks(status,created_at,id);
CREATE INDEX tasks_recent ON tasks(created_at DESC,id DESC);
CREATE INDEX tasks_type_recent ON tasks(type,created_at DESC,id DESC);
CREATE INDEX tasks_active_comic ON tasks(comic_id,type) WHERE status IN ('queued','running','cancelling');
CREATE UNIQUE INDEX tasks_active_source ON tasks(source_path) WHERE type IN ('directory_import','archive_import') AND status IN ('queued','running','cancelling');
CREATE UNIQUE INDEX tasks_active_target ON tasks(type,target_type,target_id) WHERE type IN ('image_lq','storage_refresh','hq_release','directory_scan','archive_export','video_transcode','media_upload','metadata_refresh','lifecycle','lifecycle_audit','metadata_sync','metadata_batch','command_batch','directory_move','directory_preflight','disk_recovery','recovery_repair','ai_analysis') AND status IN ('queued','running','cancelling');
CREATE UNIQUE INDEX tasks_active_import_batch ON tasks(type,target_id) WHERE type='import_batch' AND status IN ('queued','running','cancelling');

ALTER TABLE task_batches ADD COLUMN parent_task_id TEXT REFERENCES tasks(id);
CREATE TABLE import_batch_dispatch_items (
 batch_id TEXT NOT NULL REFERENCES task_batches(id) ON DELETE CASCADE,
 position INTEGER NOT NULL CHECK(position BETWEEN 1 AND 1000),
 reference_id TEXT NOT NULL CHECK(length(reference_id) BETWEEN 1 AND 128),
 source_path TEXT NOT NULL CHECK(length(source_path)>0),
 prepared_json TEXT NOT NULL CHECK(json_valid(prepared_json) AND json_type(prepared_json)='object'),
 status TEXT NOT NULL DEFAULT 'pending' CHECK(status IN ('pending','dispatched','succeeded','failed','cancelled')),
 task_id TEXT UNIQUE REFERENCES tasks(id),
 error_code TEXT,
 PRIMARY KEY(batch_id,position), UNIQUE(batch_id,reference_id),
 CHECK((status='pending' AND task_id IS NULL AND error_code IS NULL) OR (status='dispatched' AND task_id IS NOT NULL AND error_code IS NULL) OR (status IN('succeeded','failed') AND task_id IS NOT NULL) OR status='cancelled')
);
CREATE INDEX import_batch_pending ON import_batch_dispatch_items(batch_id,position) WHERE status='pending';
CREATE INDEX import_batch_children ON import_batch_dispatch_items(batch_id,position) WHERE status='dispatched';
CREATE UNIQUE INDEX import_batch_active_source ON import_batch_dispatch_items(source_path) WHERE status IN('pending','dispatched');
