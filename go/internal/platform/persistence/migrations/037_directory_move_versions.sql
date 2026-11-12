-- 全局单调时钟防止同 ID 删除重建后复用旧版本；所有递增与业务写入同事务。
CREATE TABLE comic_change_clock (
 singleton INTEGER PRIMARY KEY CHECK(singleton=1),
 revision INTEGER NOT NULL CHECK(typeof(revision)='integer' AND revision>0)
);
INSERT INTO comic_change_clock VALUES(1,1);
CREATE TABLE comic_change_versions (
 comic_id TEXT PRIMARY KEY NOT NULL REFERENCES comics(id) ON DELETE CASCADE ON UPDATE CASCADE,
 revision INTEGER NOT NULL CHECK(typeof(revision)='integer' AND revision>0)
);
INSERT INTO comic_change_versions SELECT id,1 FROM comics;
CREATE TRIGGER comic_change_insert AFTER INSERT ON comics BEGIN
 UPDATE comic_change_clock SET revision=revision+1 WHERE singleton=1;
 INSERT INTO comic_change_versions SELECT NEW.id,revision FROM comic_change_clock WHERE singleton=1;
END;
CREATE TRIGGER comic_change_update AFTER UPDATE ON comics BEGIN
 UPDATE comic_change_clock SET revision=revision+1 WHERE singleton=1;
 UPDATE comic_change_versions SET revision=(SELECT revision FROM comic_change_clock WHERE singleton=1) WHERE comic_id IN(OLD.id,NEW.id);
END;
CREATE TRIGGER comic_change_catalogs_insert AFTER INSERT ON catalogs BEGIN
 UPDATE comic_change_clock SET revision=revision+1 WHERE singleton=1;
 UPDATE comic_change_versions SET revision=(SELECT revision FROM comic_change_clock WHERE singleton=1) WHERE comic_id IN(SELECT NEW.comic_id);
END;
CREATE TRIGGER comic_change_catalogs_update AFTER UPDATE ON catalogs BEGIN
 UPDATE comic_change_clock SET revision=revision+1 WHERE singleton=1;
 UPDATE comic_change_versions SET revision=(SELECT revision FROM comic_change_clock WHERE singleton=1) WHERE comic_id IN(SELECT OLD.comic_id UNION SELECT NEW.comic_id);
END;
CREATE TRIGGER comic_change_catalogs_delete AFTER DELETE ON catalogs BEGIN
 UPDATE comic_change_clock SET revision=revision+1 WHERE singleton=1;
 UPDATE comic_change_versions SET revision=(SELECT revision FROM comic_change_clock WHERE singleton=1) WHERE comic_id IN(SELECT OLD.comic_id);
END;
CREATE TRIGGER comic_change_chapter_reactions_insert AFTER INSERT ON chapter_reactions BEGIN
 UPDATE comic_change_clock SET revision=revision+1 WHERE singleton=1;
 UPDATE comic_change_versions SET revision=(SELECT revision FROM comic_change_clock WHERE singleton=1) WHERE comic_id IN(SELECT comic_id FROM chapters WHERE id=NEW.target_id);
END;
CREATE TRIGGER comic_change_chapter_reactions_update AFTER UPDATE ON chapter_reactions BEGIN
 UPDATE comic_change_clock SET revision=revision+1 WHERE singleton=1;
 UPDATE comic_change_versions SET revision=(SELECT revision FROM comic_change_clock WHERE singleton=1) WHERE comic_id IN(SELECT comic_id FROM chapters WHERE id=OLD.target_id UNION SELECT comic_id FROM chapters WHERE id=NEW.target_id);
END;
CREATE TRIGGER comic_change_chapter_reactions_delete AFTER DELETE ON chapter_reactions BEGIN
 UPDATE comic_change_clock SET revision=revision+1 WHERE singleton=1;
 UPDATE comic_change_versions SET revision=(SELECT revision FROM comic_change_clock WHERE singleton=1) WHERE comic_id IN(SELECT comic_id FROM chapters WHERE id=OLD.target_id);
END;
CREATE TRIGGER comic_change_chapters_insert AFTER INSERT ON chapters BEGIN
 UPDATE comic_change_clock SET revision=revision+1 WHERE singleton=1;
 UPDATE comic_change_versions SET revision=(SELECT revision FROM comic_change_clock WHERE singleton=1) WHERE comic_id IN(SELECT NEW.comic_id);
END;
CREATE TRIGGER comic_change_chapters_update AFTER UPDATE ON chapters BEGIN
 UPDATE comic_change_clock SET revision=revision+1 WHERE singleton=1;
 UPDATE comic_change_versions SET revision=(SELECT revision FROM comic_change_clock WHERE singleton=1) WHERE comic_id IN(SELECT OLD.comic_id UNION SELECT NEW.comic_id);
END;
CREATE TRIGGER comic_change_chapters_delete AFTER DELETE ON chapters BEGIN
 UPDATE comic_change_clock SET revision=revision+1 WHERE singleton=1;
 UPDATE comic_change_versions SET revision=(SELECT revision FROM comic_change_clock WHERE singleton=1) WHERE comic_id IN(SELECT OLD.comic_id);
END;
CREATE TRIGGER comic_change_comic_reactions_insert AFTER INSERT ON comic_reactions BEGIN
 UPDATE comic_change_clock SET revision=revision+1 WHERE singleton=1;
 UPDATE comic_change_versions SET revision=(SELECT revision FROM comic_change_clock WHERE singleton=1) WHERE comic_id IN(SELECT NEW.target_id);
END;
CREATE TRIGGER comic_change_comic_reactions_update AFTER UPDATE ON comic_reactions BEGIN
 UPDATE comic_change_clock SET revision=revision+1 WHERE singleton=1;
 UPDATE comic_change_versions SET revision=(SELECT revision FROM comic_change_clock WHERE singleton=1) WHERE comic_id IN(SELECT OLD.target_id UNION SELECT NEW.target_id);
END;
CREATE TRIGGER comic_change_comic_reactions_delete AFTER DELETE ON comic_reactions BEGIN
 UPDATE comic_change_clock SET revision=revision+1 WHERE singleton=1;
 UPDATE comic_change_versions SET revision=(SELECT revision FROM comic_change_clock WHERE singleton=1) WHERE comic_id IN(SELECT OLD.target_id);
END;
CREATE TRIGGER comic_change_comic_source_metadata_insert AFTER INSERT ON comic_source_metadata BEGIN
 UPDATE comic_change_clock SET revision=revision+1 WHERE singleton=1;
 UPDATE comic_change_versions SET revision=(SELECT revision FROM comic_change_clock WHERE singleton=1) WHERE comic_id IN(SELECT NEW.comic_id);
END;
CREATE TRIGGER comic_change_comic_source_metadata_update AFTER UPDATE ON comic_source_metadata BEGIN
 UPDATE comic_change_clock SET revision=revision+1 WHERE singleton=1;
 UPDATE comic_change_versions SET revision=(SELECT revision FROM comic_change_clock WHERE singleton=1) WHERE comic_id IN(SELECT OLD.comic_id UNION SELECT NEW.comic_id);
END;
CREATE TRIGGER comic_change_comic_source_metadata_delete AFTER DELETE ON comic_source_metadata BEGIN
 UPDATE comic_change_clock SET revision=revision+1 WHERE singleton=1;
 UPDATE comic_change_versions SET revision=(SELECT revision FROM comic_change_clock WHERE singleton=1) WHERE comic_id IN(SELECT OLD.comic_id);
END;
CREATE TRIGGER comic_change_comic_tags_insert AFTER INSERT ON comic_tags BEGIN
 UPDATE comic_change_clock SET revision=revision+1 WHERE singleton=1;
 UPDATE comic_change_versions SET revision=(SELECT revision FROM comic_change_clock WHERE singleton=1) WHERE comic_id IN(SELECT NEW.comic_id);
END;
CREATE TRIGGER comic_change_comic_tags_update AFTER UPDATE ON comic_tags BEGIN
 UPDATE comic_change_clock SET revision=revision+1 WHERE singleton=1;
 UPDATE comic_change_versions SET revision=(SELECT revision FROM comic_change_clock WHERE singleton=1) WHERE comic_id IN(SELECT OLD.comic_id UNION SELECT NEW.comic_id);
END;
CREATE TRIGGER comic_change_comic_tags_delete AFTER DELETE ON comic_tags BEGIN
 UPDATE comic_change_clock SET revision=revision+1 WHERE singleton=1;
 UPDATE comic_change_versions SET revision=(SELECT revision FROM comic_change_clock WHERE singleton=1) WHERE comic_id IN(SELECT OLD.comic_id);
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
CREATE TRIGGER comic_change_lifecycle_entries_insert AFTER INSERT ON lifecycle_entries BEGIN
 UPDATE comic_change_clock SET revision=revision+1 WHERE singleton=1;
 UPDATE comic_change_versions SET revision=(SELECT revision FROM comic_change_clock WHERE singleton=1) WHERE comic_id IN(SELECT NEW.comic_id);
END;
CREATE TRIGGER comic_change_lifecycle_entries_update AFTER UPDATE ON lifecycle_entries BEGIN
 UPDATE comic_change_clock SET revision=revision+1 WHERE singleton=1;
 UPDATE comic_change_versions SET revision=(SELECT revision FROM comic_change_clock WHERE singleton=1) WHERE comic_id IN(SELECT OLD.comic_id UNION SELECT NEW.comic_id);
END;
CREATE TRIGGER comic_change_lifecycle_entries_delete AFTER DELETE ON lifecycle_entries BEGIN
 UPDATE comic_change_clock SET revision=revision+1 WHERE singleton=1;
 UPDATE comic_change_versions SET revision=(SELECT revision FROM comic_change_clock WHERE singleton=1) WHERE comic_id IN(SELECT OLD.comic_id);
END;
CREATE TRIGGER comic_change_media_insert AFTER INSERT ON media BEGIN
 UPDATE comic_change_clock SET revision=revision+1 WHERE singleton=1;
 UPDATE comic_change_versions SET revision=(SELECT revision FROM comic_change_clock WHERE singleton=1) WHERE comic_id IN(SELECT comic_id FROM chapters WHERE id=NEW.chapter_id);
END;
CREATE TRIGGER comic_change_media_update AFTER UPDATE ON media BEGIN
 UPDATE comic_change_clock SET revision=revision+1 WHERE singleton=1;
 UPDATE comic_change_versions SET revision=(SELECT revision FROM comic_change_clock WHERE singleton=1) WHERE comic_id IN(SELECT comic_id FROM chapters WHERE id=OLD.chapter_id UNION SELECT comic_id FROM chapters WHERE id=NEW.chapter_id);
END;
CREATE TRIGGER comic_change_media_delete AFTER DELETE ON media BEGIN
 UPDATE comic_change_clock SET revision=revision+1 WHERE singleton=1;
 UPDATE comic_change_versions SET revision=(SELECT revision FROM comic_change_clock WHERE singleton=1) WHERE comic_id IN(SELECT comic_id FROM chapters WHERE id=OLD.chapter_id);
END;
CREATE TRIGGER comic_change_media_reactions_insert AFTER INSERT ON media_reactions BEGIN
 UPDATE comic_change_clock SET revision=revision+1 WHERE singleton=1;
 UPDATE comic_change_versions SET revision=(SELECT revision FROM comic_change_clock WHERE singleton=1) WHERE comic_id IN(SELECT ch.comic_id FROM media m JOIN chapters ch ON ch.id=m.chapter_id WHERE m.id=NEW.target_id);
END;
CREATE TRIGGER comic_change_media_reactions_update AFTER UPDATE ON media_reactions BEGIN
 UPDATE comic_change_clock SET revision=revision+1 WHERE singleton=1;
 UPDATE comic_change_versions SET revision=(SELECT revision FROM comic_change_clock WHERE singleton=1) WHERE comic_id IN(SELECT ch.comic_id FROM media m JOIN chapters ch ON ch.id=m.chapter_id WHERE m.id=OLD.target_id UNION SELECT ch.comic_id FROM media m JOIN chapters ch ON ch.id=m.chapter_id WHERE m.id=NEW.target_id);
END;
CREATE TRIGGER comic_change_media_reactions_delete AFTER DELETE ON media_reactions BEGIN
 UPDATE comic_change_clock SET revision=revision+1 WHERE singleton=1;
 UPDATE comic_change_versions SET revision=(SELECT revision FROM comic_change_clock WHERE singleton=1) WHERE comic_id IN(SELECT ch.comic_id FROM media m JOIN chapters ch ON ch.id=m.chapter_id WHERE m.id=OLD.target_id);
END;
CREATE TRIGGER comic_change_media_thumbnails_insert AFTER INSERT ON media_thumbnails BEGIN
 UPDATE comic_change_clock SET revision=revision+1 WHERE singleton=1;
 UPDATE comic_change_versions SET revision=(SELECT revision FROM comic_change_clock WHERE singleton=1) WHERE comic_id IN(SELECT ch.comic_id FROM media m JOIN chapters ch ON ch.id=m.chapter_id WHERE m.id=NEW.media_id);
END;
CREATE TRIGGER comic_change_media_thumbnails_update AFTER UPDATE ON media_thumbnails BEGIN
 UPDATE comic_change_clock SET revision=revision+1 WHERE singleton=1;
 UPDATE comic_change_versions SET revision=(SELECT revision FROM comic_change_clock WHERE singleton=1) WHERE comic_id IN(SELECT ch.comic_id FROM media m JOIN chapters ch ON ch.id=m.chapter_id WHERE m.id=OLD.media_id UNION SELECT ch.comic_id FROM media m JOIN chapters ch ON ch.id=m.chapter_id WHERE m.id=NEW.media_id);
END;
CREATE TRIGGER comic_change_media_thumbnails_delete AFTER DELETE ON media_thumbnails BEGIN
 UPDATE comic_change_clock SET revision=revision+1 WHERE singleton=1;
 UPDATE comic_change_versions SET revision=(SELECT revision FROM comic_change_clock WHERE singleton=1) WHERE comic_id IN(SELECT ch.comic_id FROM media m JOIN chapters ch ON ch.id=m.chapter_id WHERE m.id=OLD.media_id);
END;
CREATE TRIGGER comic_change_reading_progress_insert AFTER INSERT ON reading_progress BEGIN
 UPDATE comic_change_clock SET revision=revision+1 WHERE singleton=1;
 UPDATE comic_change_versions SET revision=(SELECT revision FROM comic_change_clock WHERE singleton=1) WHERE comic_id IN(SELECT NEW.comic_id);
END;
CREATE TRIGGER comic_change_reading_progress_update AFTER UPDATE ON reading_progress BEGIN
 UPDATE comic_change_clock SET revision=revision+1 WHERE singleton=1;
 UPDATE comic_change_versions SET revision=(SELECT revision FROM comic_change_clock WHERE singleton=1) WHERE comic_id IN(SELECT OLD.comic_id UNION SELECT NEW.comic_id);
END;
CREATE TRIGGER comic_change_reading_progress_delete AFTER DELETE ON reading_progress BEGIN
 UPDATE comic_change_clock SET revision=revision+1 WHERE singleton=1;
 UPDATE comic_change_versions SET revision=(SELECT revision FROM comic_change_clock WHERE singleton=1) WHERE comic_id IN(SELECT OLD.comic_id);
END;
CREATE TRIGGER comic_change_upload_retired_insert AFTER INSERT ON upload_retired BEGIN
 UPDATE comic_change_clock SET revision=revision+1 WHERE singleton=1;
 UPDATE comic_change_versions SET revision=(SELECT revision FROM comic_change_clock WHERE singleton=1) WHERE comic_id IN(SELECT s.comic_id FROM upload_sessions s WHERE s.task_id=NEW.task_id UNION SELECT ch.comic_id FROM upload_sessions s JOIN media m ON m.id=s.replace_media_id JOIN chapters ch ON ch.id=m.chapter_id WHERE s.task_id=NEW.task_id);
END;
CREATE TRIGGER comic_change_upload_retired_update AFTER UPDATE ON upload_retired BEGIN
 UPDATE comic_change_clock SET revision=revision+1 WHERE singleton=1;
 UPDATE comic_change_versions SET revision=(SELECT revision FROM comic_change_clock WHERE singleton=1) WHERE comic_id IN(SELECT s.comic_id FROM upload_sessions s WHERE s.task_id=OLD.task_id UNION SELECT ch.comic_id FROM upload_sessions s JOIN media m ON m.id=s.replace_media_id JOIN chapters ch ON ch.id=m.chapter_id WHERE s.task_id=OLD.task_id UNION SELECT s.comic_id FROM upload_sessions s WHERE s.task_id=NEW.task_id UNION SELECT ch.comic_id FROM upload_sessions s JOIN media m ON m.id=s.replace_media_id JOIN chapters ch ON ch.id=m.chapter_id WHERE s.task_id=NEW.task_id);
END;
CREATE TRIGGER comic_change_upload_retired_delete AFTER DELETE ON upload_retired BEGIN
 UPDATE comic_change_clock SET revision=revision+1 WHERE singleton=1;
 UPDATE comic_change_versions SET revision=(SELECT revision FROM comic_change_clock WHERE singleton=1) WHERE comic_id IN(SELECT s.comic_id FROM upload_sessions s WHERE s.task_id=OLD.task_id UNION SELECT ch.comic_id FROM upload_sessions s JOIN media m ON m.id=s.replace_media_id JOIN chapters ch ON ch.id=m.chapter_id WHERE s.task_id=OLD.task_id);
END;
CREATE TRIGGER comic_change_upload_sessions_insert AFTER INSERT ON upload_sessions BEGIN
 UPDATE comic_change_clock SET revision=revision+1 WHERE singleton=1;
 UPDATE comic_change_versions SET revision=(SELECT revision FROM comic_change_clock WHERE singleton=1) WHERE comic_id IN(SELECT NEW.comic_id UNION SELECT ch.comic_id FROM media m JOIN chapters ch ON ch.id=m.chapter_id WHERE m.id=NEW.replace_media_id);
END;
CREATE TRIGGER comic_change_upload_sessions_update AFTER UPDATE ON upload_sessions BEGIN
 UPDATE comic_change_clock SET revision=revision+1 WHERE singleton=1;
 UPDATE comic_change_versions SET revision=(SELECT revision FROM comic_change_clock WHERE singleton=1) WHERE comic_id IN(SELECT OLD.comic_id UNION SELECT ch.comic_id FROM media m JOIN chapters ch ON ch.id=m.chapter_id WHERE m.id=OLD.replace_media_id UNION SELECT NEW.comic_id UNION SELECT ch.comic_id FROM media m JOIN chapters ch ON ch.id=m.chapter_id WHERE m.id=NEW.replace_media_id);
END;
CREATE TRIGGER comic_change_upload_sessions_delete AFTER DELETE ON upload_sessions BEGIN
 UPDATE comic_change_clock SET revision=revision+1 WHERE singleton=1;
 UPDATE comic_change_versions SET revision=(SELECT revision FROM comic_change_clock WHERE singleton=1) WHERE comic_id IN(SELECT OLD.comic_id UNION SELECT ch.comic_id FROM media m JOIN chapters ch ON ch.id=m.chapter_id WHERE m.id=OLD.replace_media_id);
END;
CREATE TRIGGER comic_change_categories_update AFTER UPDATE ON categories BEGIN
 UPDATE comic_change_clock SET revision=revision+1 WHERE singleton=1;
 UPDATE comic_change_versions SET revision=(SELECT revision FROM comic_change_clock WHERE singleton=1) WHERE comic_id IN(SELECT id FROM comics WHERE category_id IN(OLD.id,NEW.id));
END;
CREATE TRIGGER comic_change_tags_update AFTER UPDATE ON tags BEGIN
 UPDATE comic_change_clock SET revision=revision+1 WHERE singleton=1;
 UPDATE comic_change_versions SET revision=(SELECT revision FROM comic_change_clock WHERE singleton=1) WHERE comic_id IN(SELECT comic_id FROM comic_tags WHERE tag_id IN(OLD.id,NEW.id));
END;
ALTER TABLE directory_move_previews ADD COLUMN scope_data TEXT CHECK(scope_data IS NULL OR length(CAST(scope_data AS BLOB)) BETWEEN 1 AND 4194304);
ALTER TABLE directory_move_previews ADD COLUMN scope_sha TEXT CHECK(scope_sha IS NULL OR length(scope_sha)=64);
DROP TRIGGER directory_move_preview_immutable;
CREATE TRIGGER directory_move_preview_immutable BEFORE UPDATE OF id,token_hash,plan_data,plan_sha,created_at,expires_at,scope_data,scope_sha ON directory_move_previews
BEGIN SELECT RAISE(ABORT,'移出预览已冻结'); END;
ALTER TABLE directory_move_members ADD COLUMN accepted_revision INTEGER NOT NULL DEFAULT 0 CHECK(accepted_revision>=0);
DROP TRIGGER directory_move_member_immutable;
CREATE TRIGGER directory_move_member_immutable BEFORE UPDATE OF task_id,position,comic_id,inventory_sha,accepted_revision ON directory_move_members
BEGIN SELECT RAISE(ABORT,'移出范围已冻结'); END;