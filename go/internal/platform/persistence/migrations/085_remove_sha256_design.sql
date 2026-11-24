-- 去除应用层 SHA-256 设计：文件完整性改用路径、大小、修改时间和文件身份；
-- 计划、审计、确认令牌改为规范文本/原始令牌；不保留旧列或兼容读取分支。
PRAGMA legacy_alter_table=ON;

DROP VIEW media_file_ownership;
DROP TRIGGER sidecar_media_update;

CREATE TABLE image_lq_new (
 media_id TEXT PRIMARY KEY NOT NULL REFERENCES media(id) ON DELETE CASCADE,
 relative_path TEXT NOT NULL UNIQUE,
 size INTEGER NOT NULL CHECK(size>0), width INTEGER NOT NULL CHECK(width>0), height INTEGER NOT NULL CHECK(height>0),
 max_long_edge INTEGER, quality INTEGER, valid INTEGER NOT NULL DEFAULT 1 CHECK(valid IN(0,1)),
 task_id TEXT REFERENCES tasks(id), updated_at TEXT NOT NULL,
 origin TEXT NOT NULL DEFAULT 'generated' CHECK(origin IN ('generated','imported','recovered')),
 original_state TEXT,
 CHECK((max_long_edge IS NULL)=(quality IS NULL)),
 CHECK((origin='generated' AND task_id IS NOT NULL AND max_long_edge IS NOT NULL AND original_state IS NULL)
   OR (origin='imported' AND task_id IS NULL)
   OR (origin='recovered' AND task_id IS NOT NULL)),
 CHECK(original_state IS NULL OR (original_state IN ('missing','released','unbound') AND origin IN ('imported','recovered')))
);
INSERT INTO image_lq_new(media_id,relative_path,size,width,height,max_long_edge,quality,valid,task_id,updated_at,origin,original_state)
 SELECT media_id,relative_path,size,width,height,max_long_edge,quality,valid,task_id,updated_at,origin,original_state FROM image_lq;
DROP TABLE image_lq;
ALTER TABLE image_lq_new RENAME TO image_lq;

CREATE TABLE upload_sessions_new (
 id TEXT PRIMARY KEY NOT NULL, comic_id TEXT NOT NULL, chapter_id TEXT NOT NULL, replace_media_id TEXT,
 status TEXT NOT NULL CHECK(status IN ('active','cancelled','expired','submitted','activated','completed')),
 total_bytes INTEGER NOT NULL CHECK(total_bytes BETWEEN 1 AND 107374182400),
 created_at TEXT NOT NULL, expires_at TEXT NOT NULL,
 request_key TEXT NOT NULL, idempotency_key TEXT UNIQUE,
 task_id TEXT UNIQUE REFERENCES tasks(id),
 previous_json TEXT CHECK(previous_json IS NULL OR (json_valid(previous_json) AND json_type(previous_json)='object')),
 cleaned INTEGER NOT NULL DEFAULT 0 CHECK(cleaned IN(0,1)),
 prior_json TEXT CHECK(prior_json IS NULL OR (json_valid(prior_json) AND json_type(prior_json)='object'))
);
INSERT INTO upload_sessions_new(id,comic_id,chapter_id,replace_media_id,status,total_bytes,created_at,expires_at,request_key,idempotency_key,task_id,previous_json,cleaned,prior_json)
 SELECT id,comic_id,chapter_id,replace_media_id,status,total_bytes,created_at,expires_at,request_hash,idempotency_key,task_id,previous_json,cleaned,prior_json FROM upload_sessions;
DROP TABLE upload_sessions;
ALTER TABLE upload_sessions_new RENAME TO upload_sessions;

CREATE TABLE metadata_sync_runs_new (
 task_id TEXT PRIMARY KEY NOT NULL REFERENCES tasks(id), comic_id TEXT NOT NULL, requested_revision INTEGER NOT NULL CHECK(requested_revision>0),
 observed_revision INTEGER CHECK(observed_revision IS NULL OR observed_revision>0), observed_at TEXT,
 published INTEGER NOT NULL DEFAULT 0 CHECK(published IN(0,1))
);
INSERT INTO metadata_sync_runs_new(task_id,comic_id,requested_revision,observed_revision,observed_at,published)
 SELECT task_id,comic_id,requested_revision,observed_revision,observed_at,published FROM metadata_sync_runs;
DROP TABLE metadata_sync_runs;
ALTER TABLE metadata_sync_runs_new RENAME TO metadata_sync_runs;

CREATE TABLE recovery_results_new (
 task_id TEXT NOT NULL,
 comic_id TEXT NOT NULL,
 status TEXT NOT NULL CHECK(status IN('restored','placeholder','skipped','empty','error')),
 task_attempt INTEGER NOT NULL CHECK(task_attempt>0),
 try_count INTEGER NOT NULL CHECK(try_count BETWEEN 1 AND 1000000),
 error_code TEXT CHECK(error_code IS NULL OR length(error_code) BETWEEN 1 AND 64),
 updated_at TEXT NOT NULL,
 PRIMARY KEY(task_id,comic_id),
 FOREIGN KEY(task_id,comic_id) REFERENCES recovery_candidates(task_id,comic_id) ON DELETE CASCADE,
 CHECK((status='error' AND error_code IS NOT NULL) OR (status<>'error' AND error_code IS NULL))
);
INSERT INTO recovery_results_new(task_id,comic_id,status,task_attempt,try_count,error_code,updated_at)
 SELECT task_id,comic_id,status,task_attempt,try_count,error_code,updated_at FROM recovery_results;
DROP TABLE recovery_results;
ALTER TABLE recovery_results_new RENAME TO recovery_results;

CREATE TABLE recovery_repairs_new (
 task_id TEXT PRIMARY KEY NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
 comic_id TEXT NOT NULL,
 expected_version INTEGER NOT NULL CHECK(expected_version>0),
 expected_structure_version INTEGER NOT NULL CHECK(expected_structure_version>0),
 marker_created_at TEXT NOT NULL,
 marker_task_id TEXT,
 completed INTEGER NOT NULL DEFAULT 0 CHECK(completed IN(0,1)),
 completed_at TEXT,
 version INTEGER,
 structure_version INTEGER,
 preserved_metadata INTEGER CHECK(preserved_metadata IS NULL OR preserved_metadata IN(0,1)),
 CHECK((completed=0 AND completed_at IS NULL AND version IS NULL AND structure_version IS NULL AND preserved_metadata IS NULL)
 OR (completed=1 AND completed_at IS NOT NULL AND version IS NOT NULL AND structure_version IS NOT NULL AND version>expected_version AND structure_version>expected_structure_version AND preserved_metadata IS NOT NULL))
);
INSERT INTO recovery_repairs_new(task_id,comic_id,expected_version,expected_structure_version,marker_created_at,marker_task_id,completed,completed_at,version,structure_version,preserved_metadata)
 SELECT task_id,comic_id,expected_version,expected_structure_version,marker_created_at,marker_task_id,completed,completed_at,version,structure_version,preserved_metadata FROM recovery_repairs;
DROP TABLE recovery_repairs;
ALTER TABLE recovery_repairs_new RENAME TO recovery_repairs;

CREATE TABLE lifecycle_confirmations_new (
 token TEXT PRIMARY KEY NOT NULL, entry_id TEXT NOT NULL REFERENCES lifecycle_entries(id),
 scope_stamp TEXT NOT NULL, retention_days INTEGER NOT NULL CHECK(retention_days BETWEEN 0 AND 3650),
 expires_at TEXT NOT NULL, consumed_by TEXT UNIQUE REFERENCES tasks(id)
);
INSERT INTO lifecycle_confirmations_new(token,entry_id,scope_stamp,retention_days,expires_at,consumed_by)
 SELECT token_hash,entry_id,scope_hash,retention_days,expires_at,consumed_by FROM lifecycle_confirmations;
DROP TABLE lifecycle_confirmations;
ALTER TABLE lifecycle_confirmations_new RENAME TO lifecycle_confirmations;

CREATE TABLE lifecycle_cover_sources_new (
 entry_id TEXT PRIMARY KEY NOT NULL REFERENCES lifecycle_entries(id),
 source_json TEXT NOT NULL CHECK(json_valid(source_json) AND length(CAST(source_json AS BLOB))<=8192),
 source_key TEXT NOT NULL
);
INSERT INTO lifecycle_cover_sources_new(entry_id,source_json,source_key)
 SELECT entry_id,source_json,source_key FROM lifecycle_cover_sources;
DROP TABLE lifecycle_cover_sources;
ALTER TABLE lifecycle_cover_sources_new RENAME TO lifecycle_cover_sources;

CREATE TABLE lifecycle_purge_entries_new (
 task_id TEXT NOT NULL REFERENCES lifecycle_runs(task_id), entry_id TEXT NOT NULL REFERENCES lifecycle_entries(id),
 plan_stamp TEXT NOT NULL, PRIMARY KEY(task_id,entry_id)
);
INSERT INTO lifecycle_purge_entries_new(task_id,entry_id,plan_stamp)
 SELECT task_id,entry_id,plan_hash FROM lifecycle_purge_entries;
DROP TABLE lifecycle_purge_entries;
ALTER TABLE lifecycle_purge_entries_new RENAME TO lifecycle_purge_entries;

CREATE TABLE lifecycle_audits_new (
 task_id TEXT PRIMARY KEY NOT NULL REFERENCES tasks(id), entry_id TEXT NOT NULL REFERENCES lifecycle_entries(id),
 owner_task_id TEXT REFERENCES tasks(id), snapshot_stamp TEXT NOT NULL,
 total_files INTEGER NOT NULL CHECK(total_files BETWEEN 0 AND 100000), complete INTEGER NOT NULL DEFAULT 0 CHECK(complete IN(0,1)),
 summary_json TEXT CHECK(summary_json IS NULL OR json_valid(summary_json)), created_at TEXT NOT NULL
);
INSERT INTO lifecycle_audits_new(task_id,entry_id,owner_task_id,snapshot_stamp,total_files,complete,summary_json,created_at)
 SELECT task_id,entry_id,owner_task_id,snapshot_hash,total_files,complete,summary_json,created_at FROM lifecycle_audits;
DROP TABLE lifecycle_audits;
ALTER TABLE lifecycle_audits_new RENAME TO lifecycle_audits;

CREATE TABLE batch_confirmations_new (
 preview_id TEXT PRIMARY KEY NOT NULL REFERENCES batch_previews(id) ON DELETE CASCADE,
 token TEXT NOT NULL UNIQUE CHECK(length(token)=64 AND token NOT GLOB '*[^0-9a-f]*'),
 image_count INTEGER NOT NULL CHECK(image_count>=0), original_bytes INTEGER NOT NULL CHECK(original_bytes>=0)
);
INSERT INTO batch_confirmations_new(preview_id,token,image_count,original_bytes)
 SELECT preview_id,token_hash,image_count,original_bytes FROM batch_confirmations;
DROP TABLE batch_confirmations;
ALTER TABLE batch_confirmations_new RENAME TO batch_confirmations;

CREATE TABLE upload_parts_new (
 session_id TEXT NOT NULL, file_id TEXT NOT NULL, part INTEGER NOT NULL CHECK(part BETWEEN 0 AND 1279),
 size INTEGER NOT NULL CHECK(size BETWEEN 1 AND 21474836480),
 PRIMARY KEY(session_id,file_id,part), FOREIGN KEY(session_id,file_id) REFERENCES upload_files(session_id,id) ON DELETE CASCADE
);
INSERT INTO upload_parts_new(session_id,file_id,part,size)
 SELECT session_id,file_id,part,size FROM upload_parts;
DROP TABLE upload_parts;
ALTER TABLE upload_parts_new RENAME TO upload_parts;

CREATE TABLE export_requests_new (
 task_id TEXT PRIMARY KEY NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
 snapshot_json TEXT NOT NULL CHECK(json_valid(snapshot_json))
);
INSERT INTO export_requests_new(task_id,snapshot_json)
 SELECT task_id,snapshot_json FROM export_requests;
DROP TABLE export_requests;
ALTER TABLE export_requests_new RENAME TO export_requests;

CREATE TABLE directory_move_plans_new (
 id TEXT PRIMARY KEY NOT NULL,
 plan_data TEXT NOT NULL CHECK(length(CAST(plan_data AS BLOB)) BETWEEN 1 AND 67108864),
 created_at TEXT NOT NULL,
 book_count INTEGER CHECK(book_count BETWEEN 1 AND 10000),
 file_count INTEGER CHECK(file_count BETWEEN 0 AND 100000),
 observed_bytes INTEGER CHECK(observed_bytes BETWEEN 0 AND 107374182400)
);
INSERT INTO directory_move_plans_new(id,plan_data,created_at,book_count,file_count,observed_bytes)
 SELECT id,plan_data,created_at,book_count,file_count,observed_bytes FROM directory_move_plans;
DROP TABLE directory_move_plans;
ALTER TABLE directory_move_plans_new RENAME TO directory_move_plans;

CREATE TABLE directory_move_members_new (
 task_id TEXT NOT NULL REFERENCES directory_move_runs(task_id),
 position INTEGER NOT NULL CHECK(position BETWEEN 1 AND 10000),
 comic_id TEXT NOT NULL,
 detached INTEGER NOT NULL DEFAULT 0 CHECK(detached IN(0,1)), accepted_revision INTEGER NOT NULL DEFAULT 0 CHECK(accepted_revision>=0),
 PRIMARY KEY(task_id,position), UNIQUE(task_id,comic_id)
);
INSERT INTO directory_move_members_new(task_id,position,comic_id,detached,accepted_revision)
 SELECT task_id,position,comic_id,detached,accepted_revision FROM directory_move_members;
DROP TABLE directory_move_members;
ALTER TABLE directory_move_members_new RENAME TO directory_move_members;

ALTER TABLE media DROP COLUMN sha256;
ALTER TABLE comic_covers DROP COLUMN sha256;
ALTER TABLE upload_retired DROP COLUMN sha256;
ALTER TABLE metadata_refresh_runs DROP COLUMN sidecar_sha;

ALTER TABLE tasks RENAME COLUMN request_hash TO request_key;
ALTER TABLE task_batches RENAME COLUMN request_hash TO request_key;

CREATE UNIQUE INDEX directory_move_member_lock ON directory_move_members(comic_id) WHERE detached=0;
CREATE INDEX directory_move_plan_created ON directory_move_plans(created_at);
CREATE INDEX lifecycle_audits_latest ON lifecycle_audits(entry_id,created_at DESC,task_id DESC);
CREATE INDEX lifecycle_confirmations_expiry ON lifecycle_confirmations(expires_at);
CREATE INDEX recovery_results_state ON recovery_results(task_id,status,task_attempt,comic_id);
CREATE INDEX upload_sessions_active_comic ON upload_sessions(comic_id) WHERE status IN('active','submitted','activated');
CREATE INDEX upload_sessions_chapter ON upload_sessions(chapter_id) WHERE status IN ('active','submitted','activated');
CREATE INDEX upload_sessions_cleanup ON upload_sessions(id) WHERE status IN ('cancelled','expired') AND cleaned=0;
CREATE INDEX upload_sessions_expiry ON upload_sessions(expires_at,id) WHERE status='active';
CREATE INDEX upload_sessions_unfinished ON upload_sessions(comic_id,task_id) WHERE status IN ('submitted','activated');

CREATE TRIGGER sidecar_image_lq_delete AFTER DELETE ON image_lq
BEGIN
 INSERT INTO comic_sidecars(comic_id,requested_at) SELECT id,strftime('%Y-%m-%dT%H:%M:%f000000Z','now') FROM comics WHERE id IN(SELECT ch.comic_id FROM media m JOIN chapters ch ON ch.id=m.chapter_id WHERE m.id=OLD.media_id)
 ON CONFLICT(comic_id) DO UPDATE SET revision=revision+1,requested_at=excluded.requested_at;
END;
CREATE TRIGGER sidecar_image_lq_insert AFTER INSERT ON image_lq
BEGIN
 INSERT INTO comic_sidecars(comic_id,requested_at) SELECT id,strftime('%Y-%m-%dT%H:%M:%f000000Z','now') FROM comics WHERE id IN(SELECT ch.comic_id FROM media m JOIN chapters ch ON ch.id=m.chapter_id WHERE m.id=NEW.media_id)
 ON CONFLICT(comic_id) DO UPDATE SET revision=revision+1,requested_at=excluded.requested_at;
END;
CREATE TRIGGER sidecar_image_lq_update AFTER UPDATE OF media_id,task_id,relative_path,size,width,height,max_long_edge,quality,valid,origin,original_state ON image_lq
WHEN OLD.media_id IS NOT NEW.media_id
 OR OLD.task_id IS NOT NEW.task_id
 OR OLD.relative_path IS NOT NEW.relative_path
 OR OLD.size IS NOT NEW.size
 OR OLD.width IS NOT NEW.width
 OR OLD.height IS NOT NEW.height
 OR OLD.max_long_edge IS NOT NEW.max_long_edge
 OR OLD.quality IS NOT NEW.quality
 OR OLD.valid IS NOT NEW.valid
 OR OLD.origin IS NOT NEW.origin
 OR OLD.original_state IS NOT NEW.original_state
BEGIN
 INSERT INTO comic_sidecars(comic_id,requested_at) SELECT id,strftime('%Y-%m-%dT%H:%M:%f000000Z','now') FROM comics WHERE id IN(SELECT ch.comic_id FROM media m JOIN chapters ch ON ch.id=m.chapter_id WHERE m.id=OLD.media_id)
 ON CONFLICT(comic_id) DO UPDATE SET revision=revision+1,requested_at=excluded.requested_at;
 INSERT INTO comic_sidecars(comic_id,requested_at) SELECT id,strftime('%Y-%m-%dT%H:%M:%f000000Z','now') FROM comics WHERE id IN(SELECT ch.comic_id FROM media m JOIN chapters ch ON ch.id=m.chapter_id WHERE m.id=NEW.media_id) AND id NOT IN(SELECT ch.comic_id FROM media m JOIN chapters ch ON ch.id=m.chapter_id WHERE m.id=OLD.media_id)
 ON CONFLICT(comic_id) DO UPDATE SET revision=revision+1,requested_at=excluded.requested_at;
END;

CREATE TRIGGER comic_change_image_lq_delete AFTER DELETE ON image_lq BEGIN
 UPDATE comic_change_clock SET revision=revision+1 WHERE singleton=1;
 UPDATE comic_change_versions SET revision=(SELECT revision FROM comic_change_clock WHERE singleton=1) WHERE comic_id IN(SELECT ch.comic_id FROM media m JOIN chapters ch ON ch.id=m.chapter_id WHERE m.id=OLD.media_id);
END;
CREATE TRIGGER comic_change_image_lq_insert AFTER INSERT ON image_lq BEGIN
 UPDATE comic_change_clock SET revision=revision+1 WHERE singleton=1;
 UPDATE comic_change_versions SET revision=(SELECT revision FROM comic_change_clock WHERE singleton=1) WHERE comic_id IN(SELECT ch.comic_id FROM media m JOIN chapters ch ON ch.id=m.chapter_id WHERE m.id=NEW.media_id);
END;
CREATE TRIGGER comic_change_image_lq_update AFTER UPDATE ON image_lq BEGIN
 UPDATE comic_change_clock SET revision=revision+1 WHERE singleton=1;
 UPDATE comic_change_versions SET revision=(SELECT revision FROM comic_change_clock WHERE singleton=1) WHERE comic_id IN(SELECT ch.comic_id FROM media m JOIN chapters ch ON ch.id=m.chapter_id WHERE m.id=OLD.media_id UNION SELECT ch.comic_id FROM media m JOIN chapters ch ON ch.id=m.chapter_id WHERE m.id=NEW.media_id);
END;

CREATE TRIGGER storage_metrics_lq_delete AFTER DELETE ON image_lq BEGIN
 INSERT INTO storage_metrics_dirty(comic_id) SELECT ch.comic_id FROM media m JOIN chapters ch ON ch.id=m.chapter_id WHERE m.id=OLD.media_id ON CONFLICT(comic_id) DO NOTHING;
END;
CREATE TRIGGER storage_metrics_lq_insert AFTER INSERT ON image_lq BEGIN
 INSERT INTO storage_metrics_dirty(comic_id) SELECT ch.comic_id FROM media m JOIN chapters ch ON ch.id=m.chapter_id WHERE m.id=NEW.media_id ON CONFLICT(comic_id) DO NOTHING;
END;
CREATE TRIGGER storage_metrics_lq_update AFTER UPDATE ON image_lq BEGIN
 INSERT INTO storage_metrics_dirty(comic_id) SELECT ch.comic_id FROM media m JOIN chapters ch ON ch.id=m.chapter_id WHERE m.id=OLD.media_id ON CONFLICT(comic_id) DO NOTHING;
 INSERT INTO storage_metrics_dirty(comic_id) SELECT ch.comic_id FROM media m JOIN chapters ch ON ch.id=m.chapter_id WHERE m.id=NEW.media_id ON CONFLICT(comic_id) DO NOTHING;
END;

CREATE TRIGGER comic_change_upload_sessions_delete AFTER DELETE ON upload_sessions BEGIN
 UPDATE comic_change_clock SET revision=revision+1 WHERE singleton=1;
 UPDATE comic_change_versions SET revision=(SELECT revision FROM comic_change_clock WHERE singleton=1) WHERE comic_id IN(SELECT OLD.comic_id UNION SELECT ch.comic_id FROM media m JOIN chapters ch ON ch.id=m.chapter_id WHERE m.id=OLD.replace_media_id);
END;
CREATE TRIGGER comic_change_upload_sessions_insert AFTER INSERT ON upload_sessions BEGIN
 UPDATE comic_change_clock SET revision=revision+1 WHERE singleton=1;
 UPDATE comic_change_versions SET revision=(SELECT revision FROM comic_change_clock WHERE singleton=1) WHERE comic_id IN(SELECT NEW.comic_id UNION SELECT ch.comic_id FROM media m JOIN chapters ch ON ch.id=m.chapter_id WHERE m.id=NEW.replace_media_id);
END;
CREATE TRIGGER comic_change_upload_sessions_update AFTER UPDATE ON upload_sessions BEGIN
 UPDATE comic_change_clock SET revision=revision+1 WHERE singleton=1;
 UPDATE comic_change_versions SET revision=(SELECT revision FROM comic_change_clock WHERE singleton=1) WHERE comic_id IN(SELECT OLD.comic_id UNION SELECT ch.comic_id FROM media m JOIN chapters ch ON ch.id=m.chapter_id WHERE m.id=OLD.replace_media_id UNION SELECT NEW.comic_id UNION SELECT ch.comic_id FROM media m JOIN chapters ch ON ch.id=m.chapter_id WHERE m.id=NEW.replace_media_id);
END;

CREATE TRIGGER recovery_result_delete BEFORE DELETE ON recovery_results
WHEN EXISTS(SELECT 1 FROM recovery_candidates c WHERE c.task_id=OLD.task_id AND c.comic_id=OLD.comic_id)
BEGIN SELECT RAISE(ABORT,'恢复结果不能被删除'); END;
CREATE TRIGGER recovery_result_identity BEFORE UPDATE OF task_id,comic_id ON recovery_results
BEGIN SELECT RAISE(ABORT,'恢复结果身份已冻结'); END;
CREATE TRIGGER recovery_result_insert BEFORE INSERT ON recovery_results
WHEN NOT EXISTS(SELECT 1 FROM recovery_runs r JOIN tasks t ON t.id=r.task_id WHERE r.task_id=NEW.task_id AND r.sealed=1 AND t.type='disk_recovery' AND t.status='running' AND t.attempt=NEW.task_attempt)
BEGIN SELECT RAISE(ABORT,'恢复执行已失效'); END;
CREATE TRIGGER recovery_result_update BEFORE UPDATE ON recovery_results
WHEN OLD.status<>'error' OR NEW.task_attempt<=OLD.task_attempt OR NEW.try_count<>OLD.try_count+1
 OR NOT EXISTS(SELECT 1 FROM recovery_runs r JOIN tasks t ON t.id=r.task_id WHERE r.task_id=NEW.task_id AND r.sealed=1 AND t.type='disk_recovery' AND t.status='running' AND t.attempt=NEW.task_attempt)
BEGIN SELECT RAISE(ABORT,'恢复结果不能被改写'); END;

CREATE TRIGGER batch_confirmation_immutable BEFORE UPDATE ON batch_confirmations
BEGIN SELECT RAISE(ABORT,'batch confirmation immutable'); END;

CREATE TRIGGER directory_move_detachment_monotonic BEFORE UPDATE OF detached ON directory_move_members WHEN OLD.detached=1 AND NEW.detached<>1
BEGIN SELECT RAISE(ABORT,'对象已脱管'); END;
CREATE TRIGGER directory_move_member_immutable BEFORE UPDATE OF task_id,position,comic_id,accepted_revision ON directory_move_members
BEGIN SELECT RAISE(ABORT,'移出范围已冻结'); END;
CREATE TRIGGER directory_move_plan_immutable BEFORE UPDATE OF id,plan_data,created_at ON directory_move_plans
BEGIN SELECT RAISE(ABORT,'移出计划已冻结'); END;

CREATE TRIGGER sidecar_media_update AFTER UPDATE OF chapter_id,page_number,file_name,relative_path,media_type,content_type,size,width,height,video_metadata,hq_state,lifecycle_state ON media
WHEN OLD.chapter_id IS NOT NEW.chapter_id
 OR OLD.page_number IS NOT NEW.page_number
 OR OLD.file_name IS NOT NEW.file_name
 OR OLD.relative_path IS NOT NEW.relative_path
 OR OLD.media_type IS NOT NEW.media_type
 OR OLD.content_type IS NOT NEW.content_type
 OR OLD.size IS NOT NEW.size
 OR OLD.width IS NOT NEW.width
 OR OLD.height IS NOT NEW.height
 OR OLD.video_metadata IS NOT NEW.video_metadata
 OR OLD.hq_state IS NOT NEW.hq_state
 OR OLD.lifecycle_state IS NOT NEW.lifecycle_state
BEGIN
 INSERT INTO comic_sidecars(comic_id,requested_at) SELECT id,strftime('%Y-%m-%dT%H:%M:%f000000Z','now') FROM comics WHERE id IN(SELECT comic_id FROM chapters WHERE id=OLD.chapter_id)
 ON CONFLICT(comic_id) DO UPDATE SET revision=revision+1,requested_at=excluded.requested_at;
 INSERT INTO comic_sidecars(comic_id,requested_at) SELECT id,strftime('%Y-%m-%dT%H:%M:%f000000Z','now') FROM comics WHERE id IN(SELECT comic_id FROM chapters WHERE id=NEW.chapter_id) AND id NOT IN(SELECT comic_id FROM chapters WHERE id=OLD.chapter_id)
 ON CONFLICT(comic_id) DO UPDATE SET revision=revision+1,requested_at=excluded.requested_at;
END;

CREATE VIEW media_file_ownership AS
 WITH owned_media AS (
 SELECT m.*,ch.comic_id,CASE WHEN c.lifecycle_state<>'active' THEN c.lifecycle_state WHEN ch.lifecycle_state<>'active' THEN ch.lifecycle_state ELSE m.lifecycle_state END owner_state
 FROM media m JOIN chapters ch ON ch.id=m.chapter_id JOIN comics c ON c.id=ch.comic_id
 )
 SELECT comic_id,chapter_id,id media_id,'original' role,relative_path path,size,owner_state state FROM owned_media WHERE hq_state IN('available','releasing')
 UNION ALL SELECT m.comic_id,m.chapter_id,m.id,'lq',l.relative_path,l.size,m.owner_state FROM image_lq l JOIN owned_media m ON m.id=l.media_id
 UNION ALL SELECT c.comic_id,m.chapter_id,c.media_id,'cover',c.relative_path,c.size,b.lifecycle_state FROM comic_covers c JOIN comics b ON b.id=c.comic_id LEFT JOIN owned_media m ON m.id=c.media_id
 UNION ALL SELECT s.comic_id,s.chapter_id,s.replace_media_id,'retired_'||r.kind,'retired/uploads/'||s.id||'/'||r.kind||'.bin',r.size,r.status FROM upload_retired r JOIN upload_sessions s ON s.task_id=r.task_id WHERE r.status='moved'
 UNION ALL SELECT s.comic_id,s.chapter_id,f.media_id,'upload_pending',f.target_path,f.size,f.publish_state FROM upload_files f JOIN upload_sessions s ON s.id=f.session_id WHERE f.target_path IS NOT NULL AND s.status IN('submitted','cancelled','expired') AND s.cleaned=0
 UNION ALL SELECT id,NULL,NULL,'metadata','metadata/'||id||'.json',NULL,lifecycle_state FROM comics;

-- 批量预览目标戳改用稳定标识，去掉旧的 64 位摘要长度约束。
CREATE TABLE batch_preview_items_new(
 preview_id TEXT NOT NULL REFERENCES batch_previews(id) ON DELETE CASCADE,position INTEGER NOT NULL CHECK(position>0),comic_id TEXT NOT NULL,title TEXT NOT NULL,version INTEGER NOT NULL CHECK(version>=0),
 status TEXT NOT NULL CHECK(status IN('pending','blocked','succeeded','failed','dispatched','cancelled')),
 error_code TEXT,updated_version INTEGER,scope_revision INTEGER NOT NULL DEFAULT 0,image_count INTEGER NOT NULL DEFAULT 0,
 child_task_id TEXT UNIQUE REFERENCES tasks(id),wait_reason TEXT,next_try_at TEXT NOT NULL DEFAULT '',video_count INTEGER NOT NULL DEFAULT 0 CHECK(video_count>=0),
 hq_json TEXT CHECK(hq_json IS NULL OR (json_valid(hq_json) AND json_type(hq_json)='object')),
 entry_id TEXT NOT NULL DEFAULT '',entry_stamp TEXT NOT NULL DEFAULT '',target_type TEXT NOT NULL DEFAULT 'comic',target_id TEXT NOT NULL DEFAULT '',
 dependencies_json TEXT NOT NULL DEFAULT '[]' CHECK(json_valid(dependencies_json) AND json_type(dependencies_json)='array' AND json_array_length(dependencies_json)<=2),
 purge_json TEXT CHECK(purge_json IS NULL OR (json_valid(purge_json) AND json_type(purge_json)='object')),
 covered_by INTEGER, target_stamp TEXT NOT NULL DEFAULT '', chapter_id TEXT NOT NULL DEFAULT '', target_files INTEGER CHECK(target_files IS NULL OR target_files>=0),
 PRIMARY KEY(preview_id,position),
 FOREIGN KEY(preview_id,covered_by) REFERENCES batch_preview_items(preview_id,position) ON DELETE CASCADE,
 CHECK((status='succeeded' AND (updated_version IS NOT NULL OR child_task_id IS NOT NULL OR covered_by IS NOT NULL) AND error_code IS NULL) OR (status<>'succeeded' AND updated_version IS NULL)),
 CHECK((status IN('blocked','failed','cancelled') AND error_code IS NOT NULL) OR (status IN('pending','succeeded','dispatched') AND error_code IS NULL)),
 CHECK(status<>'dispatched' OR child_task_id IS NOT NULL),
 CHECK(covered_by IS NULL OR (covered_by>0 AND covered_by<position AND entry_id<>'' AND purge_json IS NULL AND child_task_id IS NULL AND dependencies_json='[]'))
);
INSERT INTO batch_preview_items_new SELECT preview_id,position,comic_id,title,version,status,error_code,updated_version,scope_revision,image_count,child_task_id,wait_reason,next_try_at,video_count,hq_json,entry_id,entry_stamp,target_type,target_id,dependencies_json,purge_json,covered_by,target_stamp,chapter_id,target_files FROM batch_preview_items;
DROP TABLE batch_preview_items;
ALTER TABLE batch_preview_items_new RENAME TO batch_preview_items;
CREATE UNIQUE INDEX batch_active_target ON batch_preview_items(preview_id,target_type,target_id) WHERE entry_id='';
CREATE INDEX batch_child_pending ON batch_preview_items(preview_id,position) WHERE status='dispatched';
CREATE INDEX batch_covered_pending ON batch_preview_items(preview_id,covered_by) WHERE status='pending' AND covered_by IS NOT NULL;
CREATE UNIQUE INDEX batch_entry_target ON batch_preview_items(preview_id,entry_id) WHERE entry_id<>'';
CREATE INDEX batch_preview_pending ON batch_preview_items(preview_id,position) WHERE status='pending' AND covered_by IS NULL;
CREATE TRIGGER batch_counts_delete AFTER DELETE ON batch_preview_items
BEGIN
 UPDATE batch_preview_counts SET total_count=total_count-1,
 pending_count=pending_count-(OLD.status='pending'),blocked_count=blocked_count-(OLD.status='blocked'),
 succeeded_count=succeeded_count-(OLD.status='succeeded'),failed_count=failed_count-(OLD.status='failed'),
 active_count=active_count-(OLD.status='dispatched'),cancelled_count=cancelled_count-(OLD.status='cancelled') WHERE preview_id=OLD.preview_id;
END;
CREATE TRIGGER batch_counts_insert AFTER INSERT ON batch_preview_items
BEGIN
 UPDATE batch_preview_counts SET total_count=total_count+1,
 pending_count=pending_count+(NEW.status='pending'),blocked_count=blocked_count+(NEW.status='blocked'),
 succeeded_count=succeeded_count+(NEW.status='succeeded'),failed_count=failed_count+(NEW.status='failed'),
 active_count=active_count+(NEW.status='dispatched'),cancelled_count=cancelled_count+(NEW.status='cancelled') WHERE preview_id=NEW.preview_id;
END;
CREATE TRIGGER batch_counts_update AFTER UPDATE OF status ON batch_preview_items WHEN OLD.status IS NOT NEW.status
BEGIN
 UPDATE batch_preview_counts SET pending_count=pending_count+(NEW.status='pending')-(OLD.status='pending'),blocked_count=blocked_count+(NEW.status='blocked')-(OLD.status='blocked'),
 succeeded_count=succeeded_count+(NEW.status='succeeded')-(OLD.status='succeeded'),failed_count=failed_count+(NEW.status='failed')-(OLD.status='failed'),
 active_count=active_count+(NEW.status='dispatched')-(OLD.status='dispatched'),cancelled_count=cancelled_count+(NEW.status='cancelled')-(OLD.status='cancelled') WHERE preview_id=NEW.preview_id;
END;
CREATE TRIGGER batch_target_immutable BEFORE UPDATE OF preview_id,position,comic_id,title,version,scope_revision,image_count,video_count,hq_json,entry_id,entry_stamp,target_type,target_id,dependencies_json,purge_json,covered_by,target_stamp,chapter_id,target_files ON batch_preview_items
WHEN OLD.preview_id IS NOT NEW.preview_id OR OLD.position IS NOT NEW.position OR OLD.comic_id IS NOT NEW.comic_id OR OLD.title IS NOT NEW.title OR OLD.version IS NOT NEW.version
 OR OLD.scope_revision IS NOT NEW.scope_revision OR OLD.image_count IS NOT NEW.image_count OR OLD.video_count IS NOT NEW.video_count OR OLD.hq_json IS NOT NEW.hq_json
 OR OLD.entry_id IS NOT NEW.entry_id OR OLD.entry_stamp IS NOT NEW.entry_stamp OR OLD.target_type IS NOT NEW.target_type OR OLD.target_id IS NOT NEW.target_id OR OLD.dependencies_json IS NOT NEW.dependencies_json
 OR OLD.purge_json IS NOT NEW.purge_json OR OLD.covered_by IS NOT NEW.covered_by
 OR OLD.target_stamp IS NOT NEW.target_stamp OR OLD.chapter_id IS NOT NEW.chapter_id OR OLD.target_files IS NOT NEW.target_files
BEGIN SELECT RAISE(ABORT,'batch target immutable'); END;

PRAGMA legacy_alter_table=OFF;
