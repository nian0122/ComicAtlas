-- 轻量副本由 media_id 关联；源文件摘要不再重复登记。
DROP VIEW image_lq_state;
DROP VIEW media_file_ownership;
CREATE TABLE image_lq_next (
 media_id TEXT PRIMARY KEY NOT NULL REFERENCES media(id) ON DELETE CASCADE,
 relative_path TEXT NOT NULL UNIQUE, sha256 TEXT NOT NULL,
 size INTEGER NOT NULL CHECK(size>0), width INTEGER NOT NULL CHECK(width>0), height INTEGER NOT NULL CHECK(height>0),
 max_long_edge INTEGER, quality INTEGER, valid INTEGER NOT NULL DEFAULT 1 CHECK(valid IN (0,1)),
 task_id TEXT REFERENCES tasks(id), updated_at TEXT NOT NULL,
 origin TEXT NOT NULL DEFAULT 'generated' CHECK(origin IN ('generated','imported','recovered')),
 source_reference TEXT, original_state TEXT,
 CHECK((max_long_edge IS NULL)=(quality IS NULL)),
 CHECK((origin='generated' AND task_id IS NOT NULL AND source_reference IS NULL AND max_long_edge IS NOT NULL AND original_state IS NULL)
 OR (origin='imported' AND task_id IS NULL AND source_reference IS NOT NULL AND length(source_reference)=64 AND source_reference NOT GLOB '*[^0-9a-f]*')
 OR (origin='recovered' AND task_id IS NOT NULL AND (source_reference IS NULL OR (length(source_reference)=64 AND source_reference NOT GLOB '*[^0-9a-f]*')))),
 CHECK(original_state IS NULL OR (original_state IN ('missing','released','unbound') AND origin IN ('imported','recovered') AND source_reference IS NOT NULL))
);
INSERT INTO image_lq_next SELECT media_id,relative_path,sha256,size,width,height,max_long_edge,quality,valid,task_id,updated_at,origin,source_reference,original_state FROM image_lq;
DROP TABLE image_lq;
ALTER TABLE image_lq_next RENAME TO image_lq;

CREATE VIEW media_file_ownership AS
 WITH owned_media AS (
 SELECT m.*,ch.comic_id,CASE WHEN c.lifecycle_state<>'active' THEN c.lifecycle_state WHEN ch.lifecycle_state<>'active' THEN ch.lifecycle_state ELSE m.lifecycle_state END owner_state
 FROM media m JOIN chapters ch ON ch.id=m.chapter_id JOIN comics c ON c.id=ch.comic_id
 )
 SELECT comic_id,chapter_id,id media_id,'original' role,relative_path path,size,sha256,owner_state state FROM owned_media WHERE hq_state IN('available','releasing')
 UNION ALL SELECT m.comic_id,m.chapter_id,m.id,'lq',l.relative_path,l.size,l.sha256,m.owner_state FROM image_lq l JOIN owned_media m ON m.id=l.media_id
 UNION ALL SELECT c.comic_id,m.chapter_id,c.media_id,'cover',c.relative_path,c.size,c.sha256,b.lifecycle_state FROM comic_covers c JOIN comics b ON b.id=c.comic_id LEFT JOIN owned_media m ON m.id=c.media_id
 UNION ALL SELECT s.comic_id,s.chapter_id,s.replace_media_id,'retired_'||r.kind,'retired/uploads/'||s.id||'/'||r.kind||'.bin',r.size,r.sha256,r.status FROM upload_retired r JOIN upload_sessions s ON s.task_id=r.task_id WHERE r.status='moved'
 UNION ALL SELECT s.comic_id,s.chapter_id,f.media_id,'upload_pending',f.target_path,f.size,f.sha256,f.publish_state FROM upload_files f JOIN upload_sessions s ON s.id=f.session_id WHERE f.target_path IS NOT NULL AND s.status IN('submitted','cancelled','expired') AND s.cleaned=0
 UNION ALL SELECT id,NULL,NULL,'metadata','metadata/'||id||'.json',NULL,NULL,lifecycle_state FROM comics;

CREATE TRIGGER sidecar_image_lq_insert AFTER INSERT ON image_lq
BEGIN
 INSERT INTO comic_sidecars(comic_id,requested_at) SELECT id,strftime('%Y-%m-%dT%H:%M:%f000000Z','now') FROM comics WHERE id IN(SELECT ch.comic_id FROM media m JOIN chapters ch ON ch.id=m.chapter_id WHERE m.id=NEW.media_id)
 ON CONFLICT(comic_id) DO UPDATE SET revision=revision+1,requested_at=excluded.requested_at;
END;
CREATE TRIGGER sidecar_image_lq_update AFTER UPDATE OF media_id,task_id,relative_path,sha256,size,width,height,max_long_edge,quality,valid,origin,source_reference,original_state ON image_lq
WHEN OLD.media_id IS NOT NEW.media_id
 OR OLD.task_id IS NOT NEW.task_id
 OR OLD.relative_path IS NOT NEW.relative_path
 OR OLD.sha256 IS NOT NEW.sha256
 OR OLD.size IS NOT NEW.size
 OR OLD.width IS NOT NEW.width
 OR OLD.height IS NOT NEW.height
 OR OLD.max_long_edge IS NOT NEW.max_long_edge
 OR OLD.quality IS NOT NEW.quality
 OR OLD.valid IS NOT NEW.valid
 OR OLD.origin IS NOT NEW.origin
 OR OLD.source_reference IS NOT NEW.source_reference
 OR OLD.original_state IS NOT NEW.original_state
BEGIN
 INSERT INTO comic_sidecars(comic_id,requested_at) SELECT id,strftime('%Y-%m-%dT%H:%M:%f000000Z','now') FROM comics WHERE id IN(SELECT ch.comic_id FROM media m JOIN chapters ch ON ch.id=m.chapter_id WHERE m.id=OLD.media_id)
 ON CONFLICT(comic_id) DO UPDATE SET revision=revision+1,requested_at=excluded.requested_at;
 INSERT INTO comic_sidecars(comic_id,requested_at) SELECT id,strftime('%Y-%m-%dT%H:%M:%f000000Z','now') FROM comics WHERE id IN(SELECT ch.comic_id FROM media m JOIN chapters ch ON ch.id=m.chapter_id WHERE m.id=NEW.media_id) AND id NOT IN(SELECT ch.comic_id FROM media m JOIN chapters ch ON ch.id=m.chapter_id WHERE m.id=OLD.media_id)
 ON CONFLICT(comic_id) DO UPDATE SET revision=revision+1,requested_at=excluded.requested_at;
END;
CREATE TRIGGER sidecar_image_lq_delete AFTER DELETE ON image_lq
BEGIN
 INSERT INTO comic_sidecars(comic_id,requested_at) SELECT id,strftime('%Y-%m-%dT%H:%M:%f000000Z','now') FROM comics WHERE id IN(SELECT ch.comic_id FROM media m JOIN chapters ch ON ch.id=m.chapter_id WHERE m.id=OLD.media_id)
 ON CONFLICT(comic_id) DO UPDATE SET revision=revision+1,requested_at=excluded.requested_at;
END;

CREATE TRIGGER comic_change_image_lq_insert AFTER INSERT ON image_lq BEGIN
 UPDATE comic_change_clock SET revision=revision+1 WHERE singleton=1;
 UPDATE comic_change_versions SET revision=(SELECT revision FROM comic_change_clock WHERE singleton=1) WHERE comic_id IN(SELECT ch.comic_id FROM media m JOIN chapters ch ON ch.id=m.chapter_id WHERE m.id=NEW.media_id);
END;
CREATE TRIGGER comic_change_image_lq_update AFTER UPDATE ON image_lq BEGIN
 UPDATE comic_change_clock SET revision=revision+1 WHERE singleton=1;
 UPDATE comic_change_versions SET revision=(SELECT revision FROM comic_change_clock WHERE singleton=1) WHERE comic_id IN(SELECT ch.comic_id FROM media m JOIN chapters ch ON ch.id=m.chapter_id WHERE m.id=OLD.media_id UNION SELECT ch.comic_id FROM media m JOIN chapters ch ON ch.id=m.chapter_id WHERE m.id=NEW.media_id);
END;
CREATE TRIGGER comic_change_image_lq_delete AFTER DELETE ON image_lq BEGIN
 UPDATE comic_change_clock SET revision=revision+1 WHERE singleton=1;
 UPDATE comic_change_versions SET revision=(SELECT revision FROM comic_change_clock WHERE singleton=1) WHERE comic_id IN(SELECT ch.comic_id FROM media m JOIN chapters ch ON ch.id=m.chapter_id WHERE m.id=OLD.media_id);
END;
CREATE TRIGGER storage_metrics_lq_insert AFTER INSERT ON image_lq BEGIN
 INSERT INTO storage_metrics_dirty(comic_id) SELECT ch.comic_id FROM media m JOIN chapters ch ON ch.id=m.chapter_id WHERE m.id=NEW.media_id ON CONFLICT(comic_id) DO NOTHING;
END;
CREATE TRIGGER storage_metrics_lq_update AFTER UPDATE ON image_lq BEGIN
 INSERT INTO storage_metrics_dirty(comic_id) SELECT ch.comic_id FROM media m JOIN chapters ch ON ch.id=m.chapter_id WHERE m.id=OLD.media_id ON CONFLICT(comic_id) DO NOTHING;
 INSERT INTO storage_metrics_dirty(comic_id) SELECT ch.comic_id FROM media m JOIN chapters ch ON ch.id=m.chapter_id WHERE m.id=NEW.media_id ON CONFLICT(comic_id) DO NOTHING;
END;
CREATE TRIGGER storage_metrics_lq_delete AFTER DELETE ON image_lq BEGIN
 INSERT INTO storage_metrics_dirty(comic_id) SELECT ch.comic_id FROM media m JOIN chapters ch ON ch.id=m.chapter_id WHERE m.id=OLD.media_id ON CONFLICT(comic_id) DO NOTHING;
END;
INSERT INTO storage_metrics_dirty(comic_id) SELECT id FROM comics WHERE true ON CONFLICT(comic_id) DO NOTHING;
-- 含副本的本地资料侧车按新快照结构重写。
INSERT INTO comic_sidecars(comic_id,requested_at)
 SELECT DISTINCT ch.comic_id,strftime('%Y-%m-%dT%H:%M:%f000000Z','now') FROM image_lq l JOIN media m ON m.id=l.media_id JOIN chapters ch ON ch.id=m.chapter_id
 ON CONFLICT(comic_id) DO UPDATE SET revision=revision+1,requested_at=excluded.requested_at;
