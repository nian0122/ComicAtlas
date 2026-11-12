CREATE TABLE image_lq_next (
 media_id TEXT PRIMARY KEY NOT NULL REFERENCES media(id) ON DELETE CASCADE,
 source_sha256 TEXT NOT NULL, relative_path TEXT NOT NULL UNIQUE, sha256 TEXT NOT NULL,
 size INTEGER NOT NULL CHECK(size>0), width INTEGER NOT NULL CHECK(width>0), height INTEGER NOT NULL CHECK(height>0),
 max_long_edge INTEGER, quality INTEGER, valid INTEGER NOT NULL DEFAULT 1 CHECK(valid IN (0,1)),
 task_id TEXT REFERENCES tasks(id), updated_at TEXT NOT NULL,
 origin TEXT NOT NULL DEFAULT 'generated' CHECK(origin IN ('generated','imported','recovered')),
 source_reference TEXT,
 CHECK((max_long_edge IS NULL)=(quality IS NULL)),
 CHECK((origin='generated' AND task_id IS NOT NULL AND source_reference IS NULL AND max_long_edge IS NOT NULL)
 OR (origin='imported' AND task_id IS NULL AND source_reference IS NOT NULL AND length(source_reference)=64 AND source_reference NOT GLOB '*[^0-9a-f]*')
 OR (origin='recovered' AND task_id IS NOT NULL AND (source_reference IS NULL OR (length(source_reference)=64 AND source_reference NOT GLOB '*[^0-9a-f]*'))))
);
INSERT INTO image_lq_next SELECT l.*, CASE WHEN EXISTS(SELECT 1 FROM tasks t WHERE t.id=l.task_id AND t.type IN ('disk_recovery','recovery_repair')) THEN 'recovered' ELSE 'generated' END, NULL FROM image_lq l;
DROP TABLE image_lq;
ALTER TABLE image_lq_next RENAME TO image_lq;
CREATE TRIGGER sidecar_image_lq_insert AFTER INSERT ON image_lq
BEGIN
 INSERT INTO comic_sidecars(comic_id,requested_at) SELECT id,strftime('%Y-%m-%dT%H:%M:%f000000Z','now') FROM comics WHERE id IN(SELECT ch.comic_id FROM media m JOIN chapters ch ON ch.id=m.chapter_id WHERE m.id=NEW.media_id)
 ON CONFLICT(comic_id) DO UPDATE SET revision=revision+1,requested_at=excluded.requested_at;
END;
CREATE TRIGGER sidecar_image_lq_update AFTER UPDATE OF media_id,task_id,relative_path,sha256,source_sha256,size,width,height,max_long_edge,quality,valid,origin,source_reference ON image_lq
WHEN OLD.media_id IS NOT NEW.media_id
 OR OLD.task_id IS NOT NEW.task_id
 OR OLD.relative_path IS NOT NEW.relative_path
 OR OLD.sha256 IS NOT NEW.sha256
 OR OLD.source_sha256 IS NOT NEW.source_sha256
 OR OLD.size IS NOT NEW.size
 OR OLD.width IS NOT NEW.width
 OR OLD.height IS NOT NEW.height
 OR OLD.max_long_edge IS NOT NEW.max_long_edge
 OR OLD.quality IS NOT NEW.quality
 OR OLD.valid IS NOT NEW.valid
 OR OLD.origin IS NOT NEW.origin
 OR OLD.source_reference IS NOT NEW.source_reference
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
