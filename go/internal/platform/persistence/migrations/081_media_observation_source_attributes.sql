DROP TRIGGER sidecar_media_observations_update;
ALTER TABLE media_observations ADD COLUMN source_path TEXT NOT NULL DEFAULT '';
ALTER TABLE media_observations ADD COLUMN source_size INTEGER NOT NULL DEFAULT -1;
ALTER TABLE media_observations ADD COLUMN source_modified_ns INTEGER NOT NULL DEFAULT 0;
UPDATE media_observations AS obs SET (source_path,source_size,source_modified_ns)=(
 SELECT m.relative_path,m.size,m.modified_ns FROM media m WHERE m.id=obs.media_id AND m.sha256=obs.source_sha256
) WHERE EXISTS(SELECT 1 FROM media m WHERE m.id=obs.media_id AND m.sha256=obs.source_sha256);
ALTER TABLE media_observations DROP COLUMN source_sha256;
CREATE TRIGGER sidecar_media_observations_update AFTER UPDATE OF media_id,source_path,source_size,source_modified_ns,status ON media_observations
WHEN OLD.media_id IS NOT NEW.media_id
 OR OLD.source_path IS NOT NEW.source_path
 OR OLD.source_size IS NOT NEW.source_size
 OR OLD.source_modified_ns IS NOT NEW.source_modified_ns
 OR OLD.status IS NOT NEW.status
BEGIN
 INSERT INTO comic_sidecars(comic_id,requested_at) SELECT id,strftime('%Y-%m-%dT%H:%M:%f000000Z','now') FROM comics WHERE id IN(SELECT ch.comic_id FROM media m JOIN chapters ch ON ch.id=m.chapter_id WHERE m.id=OLD.media_id)
 ON CONFLICT(comic_id) DO UPDATE SET revision=revision+1,requested_at=excluded.requested_at;
 INSERT INTO comic_sidecars(comic_id,requested_at) SELECT id,strftime('%Y-%m-%dT%H:%M:%f000000Z','now') FROM comics WHERE id IN(SELECT ch.comic_id FROM media m JOIN chapters ch ON ch.id=m.chapter_id WHERE m.id=NEW.media_id) AND id NOT IN(SELECT ch.comic_id FROM media m JOIN chapters ch ON ch.id=m.chapter_id WHERE m.id=OLD.media_id)
 ON CONFLICT(comic_id) DO UPDATE SET revision=revision+1,requested_at=excluded.requested_at;
END;
