-- 侧车中的隐藏路径没有完整媒体记录，不虚构媒体行，也不得在刷新时重新发现。
CREATE TABLE comic_suppressed_paths (
 comic_id TEXT NOT NULL REFERENCES comics(id) ON DELETE CASCADE,
 relative_path TEXT NOT NULL,
 PRIMARY KEY(comic_id,relative_path)
);

CREATE TRIGGER suppressed_paths_insert AFTER INSERT ON comic_suppressed_paths BEGIN
 UPDATE comic_sidecars SET revision=revision+1,requested_at=strftime('%Y-%m-%dT%H:%M:%f000000Z','now') WHERE comic_id=NEW.comic_id;
 UPDATE comic_change_clock SET revision=revision+1 WHERE singleton=1;
 UPDATE comic_change_versions SET revision=(SELECT revision FROM comic_change_clock WHERE singleton=1) WHERE comic_id=NEW.comic_id;
END;
CREATE TRIGGER suppressed_paths_update AFTER UPDATE ON comic_suppressed_paths BEGIN
 UPDATE comic_sidecars SET revision=revision+1,requested_at=strftime('%Y-%m-%dT%H:%M:%f000000Z','now') WHERE comic_id IN(OLD.comic_id,NEW.comic_id);
 UPDATE comic_change_clock SET revision=revision+1 WHERE singleton=1;
 UPDATE comic_change_versions SET revision=(SELECT revision FROM comic_change_clock WHERE singleton=1) WHERE comic_id IN(OLD.comic_id,NEW.comic_id);
END;
CREATE TRIGGER suppressed_paths_delete AFTER DELETE ON comic_suppressed_paths BEGIN
 UPDATE comic_sidecars SET revision=revision+1,requested_at=strftime('%Y-%m-%dT%H:%M:%f000000Z','now') WHERE comic_id=OLD.comic_id;
 UPDATE comic_change_clock SET revision=revision+1 WHERE singleton=1;
 UPDATE comic_change_versions SET revision=(SELECT revision FROM comic_change_clock WHERE singleton=1) WHERE comic_id=OLD.comic_id;
END;
