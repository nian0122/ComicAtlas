-- 存储占用改为写入侧标记、读取侧按需重算的物化指标；读路径不再扫描全部媒体。
CREATE TABLE comic_storage_metrics (
 comic_id TEXT PRIMARY KEY NOT NULL REFERENCES comics(id) ON DELETE CASCADE,
 chapters INTEGER NOT NULL DEFAULT 0 CHECK(chapters>=0),
 images INTEGER NOT NULL DEFAULT 0 CHECK(images>=0),
 videos INTEGER NOT NULL DEFAULT 0 CHECK(videos>=0),
 hq_files INTEGER NOT NULL DEFAULT 0 CHECK(hq_files>=0),
 hq_bytes INTEGER NOT NULL DEFAULT 0 CHECK(hq_bytes>=0),
 lq_files INTEGER NOT NULL DEFAULT 0 CHECK(lq_files>=0),
 lq_bytes INTEGER NOT NULL DEFAULT 0 CHECK(lq_bytes>=0),
 current_lq INTEGER NOT NULL DEFAULT 0 CHECK(current_lq>=0),
 available_images INTEGER NOT NULL DEFAULT 0 CHECK(available_images>=0),
 released_images INTEGER NOT NULL DEFAULT 0 CHECK(released_images>=0),
 releasing_images INTEGER NOT NULL DEFAULT 0 CHECK(releasing_images>=0),
 invalid_images INTEGER NOT NULL DEFAULT 0 CHECK(invalid_images>=0),
 missing_lq INTEGER NOT NULL DEFAULT 0 CHECK(missing_lq>=0),
 available_bytes INTEGER NOT NULL DEFAULT 0 CHECK(available_bytes>=0),
 cover_files INTEGER NOT NULL DEFAULT 0 CHECK(cover_files>=0),
 cover_bytes INTEGER NOT NULL DEFAULT 0 CHECK(cover_bytes>=0)
);
-- 脏标记刻意不使用外键：删除漫画时级联触发器可能先于父行删除写入标记，孤儿由刷新清理。
-- 触发器内使用 ON CONFLICT DO NOTHING：外层 UPSERT 会让 OR IGNORE 失效并抛出唯一约束错误。
CREATE TABLE storage_metrics_dirty(comic_id TEXT PRIMARY KEY NOT NULL) WITHOUT ROWID;
INSERT INTO storage_metrics_dirty(comic_id) SELECT id FROM comics;

CREATE TRIGGER storage_metrics_comic_insert AFTER INSERT ON comics BEGIN
 INSERT INTO storage_metrics_dirty(comic_id) VALUES(NEW.id) ON CONFLICT(comic_id) DO NOTHING;
END;
CREATE TRIGGER storage_metrics_chapter_insert AFTER INSERT ON chapters BEGIN
 INSERT INTO storage_metrics_dirty(comic_id) VALUES(NEW.comic_id) ON CONFLICT(comic_id) DO NOTHING;
END;
CREATE TRIGGER storage_metrics_chapter_update AFTER UPDATE ON chapters BEGIN
 INSERT INTO storage_metrics_dirty(comic_id) VALUES(OLD.comic_id) ON CONFLICT(comic_id) DO NOTHING;
 INSERT INTO storage_metrics_dirty(comic_id) VALUES(NEW.comic_id) ON CONFLICT(comic_id) DO NOTHING;
END;
CREATE TRIGGER storage_metrics_chapter_delete AFTER DELETE ON chapters BEGIN
 INSERT INTO storage_metrics_dirty(comic_id) VALUES(OLD.comic_id) ON CONFLICT(comic_id) DO NOTHING;
END;
CREATE TRIGGER storage_metrics_media_insert AFTER INSERT ON media BEGIN
 INSERT INTO storage_metrics_dirty(comic_id) SELECT comic_id FROM chapters WHERE id=NEW.chapter_id ON CONFLICT(comic_id) DO NOTHING;
END;
CREATE TRIGGER storage_metrics_media_update AFTER UPDATE ON media BEGIN
 INSERT INTO storage_metrics_dirty(comic_id) SELECT comic_id FROM chapters WHERE id=OLD.chapter_id ON CONFLICT(comic_id) DO NOTHING;
 INSERT INTO storage_metrics_dirty(comic_id) SELECT comic_id FROM chapters WHERE id=NEW.chapter_id ON CONFLICT(comic_id) DO NOTHING;
END;
CREATE TRIGGER storage_metrics_media_delete AFTER DELETE ON media BEGIN
 INSERT INTO storage_metrics_dirty(comic_id) SELECT comic_id FROM chapters WHERE id=OLD.chapter_id ON CONFLICT(comic_id) DO NOTHING;
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
CREATE TRIGGER storage_metrics_cover_insert AFTER INSERT ON comic_covers BEGIN
 INSERT INTO storage_metrics_dirty(comic_id) VALUES(NEW.comic_id) ON CONFLICT(comic_id) DO NOTHING;
END;
CREATE TRIGGER storage_metrics_cover_update AFTER UPDATE ON comic_covers BEGIN
 INSERT INTO storage_metrics_dirty(comic_id) VALUES(OLD.comic_id) ON CONFLICT(comic_id) DO NOTHING;
 INSERT INTO storage_metrics_dirty(comic_id) VALUES(NEW.comic_id) ON CONFLICT(comic_id) DO NOTHING;
END;
CREATE TRIGGER storage_metrics_cover_delete AFTER DELETE ON comic_covers BEGIN
 INSERT INTO storage_metrics_dirty(comic_id) VALUES(OLD.comic_id) ON CONFLICT(comic_id) DO NOTHING;
END;
