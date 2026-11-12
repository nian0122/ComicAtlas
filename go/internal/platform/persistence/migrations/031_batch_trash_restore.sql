CREATE TABLE tasks_next (
 id TEXT PRIMARY KEY NOT NULL, type TEXT NOT NULL CHECK(type IN ('directory_import','archive_import','image_lq','storage_refresh','hq_release','directory_scan','archive_export','video_transcode','media_upload','metadata_refresh','lifecycle','lifecycle_audit','metadata_sync','metadata_batch','command_batch')),
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
 OR (type='command_batch' AND source_path='' AND comic_id='' AND target_type IN('image_lq','metadata_refresh','video_transcode','hq_release','comic_trash','trash_restore') AND target_id<>'' AND request_json='{}')
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
CREATE UNIQUE INDEX tasks_active_target ON tasks(type,target_type,target_id) WHERE type IN ('image_lq','storage_refresh','hq_release','directory_scan','archive_export','video_transcode','media_upload','metadata_refresh','lifecycle','lifecycle_audit','metadata_sync','metadata_batch','command_batch') AND status IN ('queued','running','cancelling');

CREATE TABLE batch_preview_items_next (
 preview_id TEXT NOT NULL REFERENCES batch_previews(id) ON DELETE CASCADE,
 position INTEGER NOT NULL CHECK(position>0),comic_id TEXT NOT NULL,title TEXT NOT NULL,version INTEGER NOT NULL CHECK(version>=0),
 status TEXT NOT NULL CHECK(status IN('pending','blocked','succeeded','failed','dispatched','cancelled')),
 error_code TEXT,updated_version INTEGER,scope_revision INTEGER NOT NULL DEFAULT 0,image_count INTEGER NOT NULL DEFAULT 0,
 child_task_id TEXT UNIQUE REFERENCES tasks(id),wait_reason TEXT,next_try_at TEXT NOT NULL DEFAULT '',video_count INTEGER NOT NULL DEFAULT 0 CHECK(video_count>=0),
 hq_json TEXT CHECK(hq_json IS NULL OR (json_valid(hq_json) AND json_type(hq_json)='object')),
 entry_id TEXT NOT NULL DEFAULT '',entry_stamp TEXT NOT NULL DEFAULT '',target_type TEXT NOT NULL DEFAULT 'comic',target_id TEXT NOT NULL DEFAULT '',
 dependencies_json TEXT NOT NULL DEFAULT '[]' CHECK(json_valid(dependencies_json) AND json_type(dependencies_json)='array' AND json_array_length(dependencies_json)<=2),
 PRIMARY KEY(preview_id,position),
 CHECK((status='succeeded' AND (updated_version IS NOT NULL OR child_task_id IS NOT NULL) AND error_code IS NULL) OR (status<>'succeeded' AND updated_version IS NULL)),
 CHECK((status IN('blocked','failed','cancelled') AND error_code IS NOT NULL) OR (status IN('pending','succeeded','dispatched') AND error_code IS NULL)),
 CHECK(status<>'dispatched' OR child_task_id IS NOT NULL)
);
INSERT INTO batch_preview_items_next(preview_id,position,comic_id,title,version,status,error_code,updated_version,scope_revision,image_count,child_task_id,wait_reason,next_try_at,video_count,hq_json,target_id)
 SELECT preview_id,position,comic_id,title,version,status,error_code,updated_version,scope_revision,image_count,child_task_id,wait_reason,next_try_at,video_count,hq_json,comic_id FROM batch_preview_items;
DROP TABLE batch_preview_items;
ALTER TABLE batch_preview_items_next RENAME TO batch_preview_items;
CREATE UNIQUE INDEX batch_comic_target ON batch_preview_items(preview_id,comic_id) WHERE entry_id='';
CREATE UNIQUE INDEX batch_entry_target ON batch_preview_items(preview_id,entry_id) WHERE entry_id<>'';
CREATE INDEX batch_preview_pending ON batch_preview_items(preview_id,position) WHERE status='pending';
CREATE INDEX batch_child_pending ON batch_preview_items(preview_id,position) WHERE status='dispatched';

DROP TRIGGER batch_counts_new;
CREATE TRIGGER batch_counts_new AFTER INSERT ON batch_previews
BEGIN
 INSERT INTO batch_preview_counts(preview_id) VALUES(NEW.id);
 INSERT INTO batch_preview_options VALUES(NEW.id,json_remove(NEW.request_json,'$.selection','$.trashSelection'));
END;
CREATE TRIGGER batch_counts_insert AFTER INSERT ON batch_preview_items
BEGIN
 UPDATE batch_preview_counts SET total_count=total_count+1,
 pending_count=pending_count+(NEW.status='pending'),blocked_count=blocked_count+(NEW.status='blocked'),
 succeeded_count=succeeded_count+(NEW.status='succeeded'),failed_count=failed_count+(NEW.status='failed'),
 active_count=active_count+(NEW.status='dispatched'),cancelled_count=cancelled_count+(NEW.status='cancelled') WHERE preview_id=NEW.preview_id;
END;
CREATE TRIGGER batch_counts_delete AFTER DELETE ON batch_preview_items
BEGIN
 UPDATE batch_preview_counts SET total_count=total_count-1,
 pending_count=pending_count-(OLD.status='pending'),blocked_count=blocked_count-(OLD.status='blocked'),
 succeeded_count=succeeded_count-(OLD.status='succeeded'),failed_count=failed_count-(OLD.status='failed'),
 active_count=active_count-(OLD.status='dispatched'),cancelled_count=cancelled_count-(OLD.status='cancelled') WHERE preview_id=OLD.preview_id;
END;
CREATE TRIGGER batch_counts_update AFTER UPDATE OF status ON batch_preview_items WHEN OLD.status IS NOT NEW.status
BEGIN
 UPDATE batch_preview_counts SET pending_count=pending_count+(NEW.status='pending')-(OLD.status='pending'),blocked_count=blocked_count+(NEW.status='blocked')-(OLD.status='blocked'),
 succeeded_count=succeeded_count+(NEW.status='succeeded')-(OLD.status='succeeded'),failed_count=failed_count+(NEW.status='failed')-(OLD.status='failed'),
 active_count=active_count+(NEW.status='dispatched')-(OLD.status='dispatched'),cancelled_count=cancelled_count+(NEW.status='cancelled')-(OLD.status='cancelled') WHERE preview_id=NEW.preview_id;
END;
CREATE TRIGGER batch_target_immutable BEFORE UPDATE OF preview_id,position,comic_id,title,version,scope_revision,image_count,video_count,hq_json,entry_id,entry_stamp,target_type,target_id,dependencies_json ON batch_preview_items
WHEN OLD.preview_id IS NOT NEW.preview_id OR OLD.position IS NOT NEW.position OR OLD.comic_id IS NOT NEW.comic_id OR OLD.title IS NOT NEW.title OR OLD.version IS NOT NEW.version
 OR OLD.scope_revision IS NOT NEW.scope_revision OR OLD.image_count IS NOT NEW.image_count OR OLD.video_count IS NOT NEW.video_count OR OLD.hq_json IS NOT NEW.hq_json
 OR OLD.entry_id IS NOT NEW.entry_id OR OLD.entry_stamp IS NOT NEW.entry_stamp OR OLD.target_type IS NOT NEW.target_type OR OLD.target_id IS NOT NEW.target_id OR OLD.dependencies_json IS NOT NEW.dependencies_json
BEGIN SELECT RAISE(ABORT,'batch target immutable'); END;
