-- 按启动期重建表协议扩展生命周期；复制期间不触发侧车修订或级联删除。
-- 先移除依赖，再建新表、复制、删除旧表、改名并恢复依赖。
DROP VIEW active_media;
DROP VIEW active_chapters;
DROP VIEW active_catalogs;
DROP VIEW active_comics;
DROP TRIGGER sidecar_comics_insert;
DROP TRIGGER sidecar_comics_update;
DROP TRIGGER sidecar_catalogs_insert;
DROP TRIGGER sidecar_catalogs_update;
DROP TRIGGER sidecar_catalogs_delete;
DROP TRIGGER sidecar_chapters_insert;
DROP TRIGGER sidecar_chapters_update;
DROP TRIGGER sidecar_chapters_delete;
DROP TRIGGER sidecar_media_insert;
DROP TRIGGER sidecar_media_update;
DROP TRIGGER sidecar_media_delete;
DROP TRIGGER sidecar_image_lq_insert;
DROP TRIGGER sidecar_image_lq_update;
DROP TRIGGER sidecar_image_lq_delete;
DROP TRIGGER sidecar_comic_tags_insert;
DROP TRIGGER sidecar_comic_tags_update;
DROP TRIGGER sidecar_comic_tags_delete;
DROP TRIGGER sidecar_comic_source_metadata_insert;
DROP TRIGGER sidecar_comic_source_metadata_update;
DROP TRIGGER sidecar_comic_source_metadata_delete;
DROP TRIGGER sidecar_media_observations_insert;
DROP TRIGGER sidecar_media_observations_update;
DROP TRIGGER sidecar_media_observations_delete;
DROP TRIGGER sidecar_tags_rename;
DROP TRIGGER sidecar_categories_rename;

CREATE TABLE comics_next (
 id TEXT PRIMARY KEY NOT NULL,
 title TEXT NOT NULL,
 created_at TEXT NOT NULL,
 author TEXT NOT NULL DEFAULT '',
 description TEXT NOT NULL DEFAULT '',
 category_id TEXT REFERENCES categories(id),
 category_name TEXT NOT NULL DEFAULT '',
 version INTEGER NOT NULL DEFAULT 1 CHECK(version>0),
 structure_version INTEGER NOT NULL DEFAULT 1 CHECK(structure_version>0),
 original_title TEXT CHECK(original_title IS NULL OR length(original_title)>0),
 lifecycle_state TEXT NOT NULL DEFAULT 'active' CHECK(lifecycle_state IN('active','trashing','trashed','restoring','purging','moving_out'))
);
INSERT INTO comics_next(id,title,created_at,author,description,category_id,category_name,version,structure_version,original_title,lifecycle_state)
 SELECT id,title,created_at,author,description,category_id,category_name,version,structure_version,original_title,lifecycle_state FROM comics;
DROP TABLE comics;
ALTER TABLE comics_next RENAME TO comics;
CREATE INDEX comics_title_order ON comics(title COLLATE COMIC_NATURAL,id);
CREATE INDEX comics_category ON comics(category_id,id);
CREATE INDEX comics_active_title ON comics(title COLLATE COMIC_NATURAL,id) WHERE lifecycle_state='active';
CREATE VIEW active_comics AS SELECT * FROM comics WHERE lifecycle_state='active';
CREATE VIEW active_catalogs AS SELECT n.* FROM catalogs n JOIN active_comics c ON c.id=n.comic_id;
CREATE VIEW active_chapters AS SELECT ch.* FROM chapters ch JOIN active_comics c ON c.id=ch.comic_id WHERE ch.lifecycle_state='active';
CREATE VIEW active_media AS SELECT m.* FROM media m JOIN active_chapters ch ON ch.id=m.chapter_id WHERE m.lifecycle_state='active';

CREATE TRIGGER sidecar_comics_insert AFTER INSERT ON comics
BEGIN
 INSERT INTO comic_sidecars(comic_id,requested_at) SELECT id,strftime('%Y-%m-%dT%H:%M:%f000000Z','now') FROM comics WHERE id IN(SELECT NEW.id)
 ON CONFLICT(comic_id) DO UPDATE SET revision=revision+1,requested_at=excluded.requested_at;
END;
CREATE TRIGGER sidecar_comics_update AFTER UPDATE OF title,original_title,created_at,author,description,category_id,category_name,version,structure_version,lifecycle_state ON comics
WHEN OLD.title IS NOT NEW.title
 OR OLD.original_title IS NOT NEW.original_title
 OR OLD.created_at IS NOT NEW.created_at
 OR OLD.author IS NOT NEW.author
 OR OLD.description IS NOT NEW.description
 OR OLD.category_id IS NOT NEW.category_id
 OR OLD.category_name IS NOT NEW.category_name
 OR OLD.version IS NOT NEW.version
 OR OLD.structure_version IS NOT NEW.structure_version
 OR OLD.lifecycle_state IS NOT NEW.lifecycle_state
BEGIN
 INSERT INTO comic_sidecars(comic_id,requested_at) SELECT id,strftime('%Y-%m-%dT%H:%M:%f000000Z','now') FROM comics WHERE id IN(SELECT OLD.id)
 ON CONFLICT(comic_id) DO UPDATE SET revision=revision+1,requested_at=excluded.requested_at;
 INSERT INTO comic_sidecars(comic_id,requested_at) SELECT id,strftime('%Y-%m-%dT%H:%M:%f000000Z','now') FROM comics WHERE id IN(SELECT NEW.id) AND id NOT IN(SELECT OLD.id)
 ON CONFLICT(comic_id) DO UPDATE SET revision=revision+1,requested_at=excluded.requested_at;
END;

CREATE TRIGGER sidecar_catalogs_insert AFTER INSERT ON catalogs
BEGIN
 INSERT INTO comic_sidecars(comic_id,requested_at) SELECT id,strftime('%Y-%m-%dT%H:%M:%f000000Z','now') FROM comics WHERE id IN(SELECT NEW.comic_id)
 ON CONFLICT(comic_id) DO UPDATE SET revision=revision+1,requested_at=excluded.requested_at;
END;
CREATE TRIGGER sidecar_catalogs_update AFTER UPDATE OF comic_id,parent_id,title,sort_order ON catalogs
WHEN OLD.comic_id IS NOT NEW.comic_id
 OR OLD.parent_id IS NOT NEW.parent_id
 OR OLD.title IS NOT NEW.title
 OR OLD.sort_order IS NOT NEW.sort_order
BEGIN
 INSERT INTO comic_sidecars(comic_id,requested_at) SELECT id,strftime('%Y-%m-%dT%H:%M:%f000000Z','now') FROM comics WHERE id IN(SELECT OLD.comic_id)
 ON CONFLICT(comic_id) DO UPDATE SET revision=revision+1,requested_at=excluded.requested_at;
 INSERT INTO comic_sidecars(comic_id,requested_at) SELECT id,strftime('%Y-%m-%dT%H:%M:%f000000Z','now') FROM comics WHERE id IN(SELECT NEW.comic_id) AND id NOT IN(SELECT OLD.comic_id)
 ON CONFLICT(comic_id) DO UPDATE SET revision=revision+1,requested_at=excluded.requested_at;
END;
CREATE TRIGGER sidecar_catalogs_delete AFTER DELETE ON catalogs
BEGIN
 INSERT INTO comic_sidecars(comic_id,requested_at) SELECT id,strftime('%Y-%m-%dT%H:%M:%f000000Z','now') FROM comics WHERE id IN(SELECT OLD.comic_id)
 ON CONFLICT(comic_id) DO UPDATE SET revision=revision+1,requested_at=excluded.requested_at;
END;

CREATE TRIGGER sidecar_chapters_insert AFTER INSERT ON chapters
BEGIN
 INSERT INTO comic_sidecars(comic_id,requested_at) SELECT id,strftime('%Y-%m-%dT%H:%M:%f000000Z','now') FROM comics WHERE id IN(SELECT NEW.comic_id)
 ON CONFLICT(comic_id) DO UPDATE SET revision=revision+1,requested_at=excluded.requested_at;
END;
CREATE TRIGGER sidecar_chapters_update AFTER UPDATE OF comic_id,catalog_id,title,global_order,sort_order,media_version,lifecycle_state ON chapters
WHEN OLD.comic_id IS NOT NEW.comic_id
 OR OLD.catalog_id IS NOT NEW.catalog_id
 OR OLD.title IS NOT NEW.title
 OR OLD.global_order IS NOT NEW.global_order
 OR OLD.sort_order IS NOT NEW.sort_order
 OR OLD.media_version IS NOT NEW.media_version
 OR OLD.lifecycle_state IS NOT NEW.lifecycle_state
BEGIN
 INSERT INTO comic_sidecars(comic_id,requested_at) SELECT id,strftime('%Y-%m-%dT%H:%M:%f000000Z','now') FROM comics WHERE id IN(SELECT OLD.comic_id)
 ON CONFLICT(comic_id) DO UPDATE SET revision=revision+1,requested_at=excluded.requested_at;
 INSERT INTO comic_sidecars(comic_id,requested_at) SELECT id,strftime('%Y-%m-%dT%H:%M:%f000000Z','now') FROM comics WHERE id IN(SELECT NEW.comic_id) AND id NOT IN(SELECT OLD.comic_id)
 ON CONFLICT(comic_id) DO UPDATE SET revision=revision+1,requested_at=excluded.requested_at;
END;
CREATE TRIGGER sidecar_chapters_delete AFTER DELETE ON chapters
BEGIN
 INSERT INTO comic_sidecars(comic_id,requested_at) SELECT id,strftime('%Y-%m-%dT%H:%M:%f000000Z','now') FROM comics WHERE id IN(SELECT OLD.comic_id)
 ON CONFLICT(comic_id) DO UPDATE SET revision=revision+1,requested_at=excluded.requested_at;
END;

CREATE TRIGGER sidecar_media_insert AFTER INSERT ON media
BEGIN
 INSERT INTO comic_sidecars(comic_id,requested_at) SELECT id,strftime('%Y-%m-%dT%H:%M:%f000000Z','now') FROM comics WHERE id IN(SELECT comic_id FROM chapters WHERE id=NEW.chapter_id)
 ON CONFLICT(comic_id) DO UPDATE SET revision=revision+1,requested_at=excluded.requested_at;
END;
CREATE TRIGGER sidecar_media_update AFTER UPDATE OF chapter_id,page_number,file_name,relative_path,media_type,content_type,size,width,height,sha256,video_metadata,hq_state,lifecycle_state ON media
WHEN OLD.chapter_id IS NOT NEW.chapter_id
 OR OLD.page_number IS NOT NEW.page_number
 OR OLD.file_name IS NOT NEW.file_name
 OR OLD.relative_path IS NOT NEW.relative_path
 OR OLD.media_type IS NOT NEW.media_type
 OR OLD.content_type IS NOT NEW.content_type
 OR OLD.size IS NOT NEW.size
 OR OLD.width IS NOT NEW.width
 OR OLD.height IS NOT NEW.height
 OR OLD.sha256 IS NOT NEW.sha256
 OR OLD.video_metadata IS NOT NEW.video_metadata
 OR OLD.hq_state IS NOT NEW.hq_state
 OR OLD.lifecycle_state IS NOT NEW.lifecycle_state
BEGIN
 INSERT INTO comic_sidecars(comic_id,requested_at) SELECT id,strftime('%Y-%m-%dT%H:%M:%f000000Z','now') FROM comics WHERE id IN(SELECT comic_id FROM chapters WHERE id=OLD.chapter_id)
 ON CONFLICT(comic_id) DO UPDATE SET revision=revision+1,requested_at=excluded.requested_at;
 INSERT INTO comic_sidecars(comic_id,requested_at) SELECT id,strftime('%Y-%m-%dT%H:%M:%f000000Z','now') FROM comics WHERE id IN(SELECT comic_id FROM chapters WHERE id=NEW.chapter_id) AND id NOT IN(SELECT comic_id FROM chapters WHERE id=OLD.chapter_id)
 ON CONFLICT(comic_id) DO UPDATE SET revision=revision+1,requested_at=excluded.requested_at;
END;
CREATE TRIGGER sidecar_media_delete AFTER DELETE ON media
BEGIN
 INSERT INTO comic_sidecars(comic_id,requested_at) SELECT id,strftime('%Y-%m-%dT%H:%M:%f000000Z','now') FROM comics WHERE id IN(SELECT comic_id FROM chapters WHERE id=OLD.chapter_id)
 ON CONFLICT(comic_id) DO UPDATE SET revision=revision+1,requested_at=excluded.requested_at;
END;

CREATE TRIGGER sidecar_image_lq_insert AFTER INSERT ON image_lq
BEGIN
 INSERT INTO comic_sidecars(comic_id,requested_at) SELECT id,strftime('%Y-%m-%dT%H:%M:%f000000Z','now') FROM comics WHERE id IN(SELECT ch.comic_id FROM media m JOIN chapters ch ON ch.id=m.chapter_id WHERE m.id=NEW.media_id)
 ON CONFLICT(comic_id) DO UPDATE SET revision=revision+1,requested_at=excluded.requested_at;
END;
CREATE TRIGGER sidecar_image_lq_update AFTER UPDATE OF media_id,task_id,relative_path,sha256,source_sha256,size,width,height,max_long_edge,quality,valid ON image_lq
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

CREATE TRIGGER sidecar_comic_tags_insert AFTER INSERT ON comic_tags
BEGIN
 INSERT INTO comic_sidecars(comic_id,requested_at) SELECT id,strftime('%Y-%m-%dT%H:%M:%f000000Z','now') FROM comics WHERE id IN(SELECT NEW.comic_id)
 ON CONFLICT(comic_id) DO UPDATE SET revision=revision+1,requested_at=excluded.requested_at;
END;
CREATE TRIGGER sidecar_comic_tags_update AFTER UPDATE OF comic_id,tag_id,source ON comic_tags
WHEN OLD.comic_id IS NOT NEW.comic_id
 OR OLD.tag_id IS NOT NEW.tag_id
 OR OLD.source IS NOT NEW.source
BEGIN
 INSERT INTO comic_sidecars(comic_id,requested_at) SELECT id,strftime('%Y-%m-%dT%H:%M:%f000000Z','now') FROM comics WHERE id IN(SELECT OLD.comic_id)
 ON CONFLICT(comic_id) DO UPDATE SET revision=revision+1,requested_at=excluded.requested_at;
 INSERT INTO comic_sidecars(comic_id,requested_at) SELECT id,strftime('%Y-%m-%dT%H:%M:%f000000Z','now') FROM comics WHERE id IN(SELECT NEW.comic_id) AND id NOT IN(SELECT OLD.comic_id)
 ON CONFLICT(comic_id) DO UPDATE SET revision=revision+1,requested_at=excluded.requested_at;
END;
CREATE TRIGGER sidecar_comic_tags_delete AFTER DELETE ON comic_tags
BEGIN
 INSERT INTO comic_sidecars(comic_id,requested_at) SELECT id,strftime('%Y-%m-%dT%H:%M:%f000000Z','now') FROM comics WHERE id IN(SELECT OLD.comic_id)
 ON CONFLICT(comic_id) DO UPDATE SET revision=revision+1,requested_at=excluded.requested_at;
END;

CREATE TRIGGER sidecar_comic_source_metadata_insert AFTER INSERT ON comic_source_metadata
BEGIN
 INSERT INTO comic_sidecars(comic_id,requested_at) SELECT id,strftime('%Y-%m-%dT%H:%M:%f000000Z','now') FROM comics WHERE id IN(SELECT NEW.comic_id)
 ON CONFLICT(comic_id) DO UPDATE SET revision=revision+1,requested_at=excluded.requested_at;
END;
CREATE TRIGGER sidecar_comic_source_metadata_update AFTER UPDATE OF comic_id,comicinfo_json ON comic_source_metadata
WHEN OLD.comic_id IS NOT NEW.comic_id
 OR OLD.comicinfo_json IS NOT NEW.comicinfo_json
BEGIN
 INSERT INTO comic_sidecars(comic_id,requested_at) SELECT id,strftime('%Y-%m-%dT%H:%M:%f000000Z','now') FROM comics WHERE id IN(SELECT OLD.comic_id)
 ON CONFLICT(comic_id) DO UPDATE SET revision=revision+1,requested_at=excluded.requested_at;
 INSERT INTO comic_sidecars(comic_id,requested_at) SELECT id,strftime('%Y-%m-%dT%H:%M:%f000000Z','now') FROM comics WHERE id IN(SELECT NEW.comic_id) AND id NOT IN(SELECT OLD.comic_id)
 ON CONFLICT(comic_id) DO UPDATE SET revision=revision+1,requested_at=excluded.requested_at;
END;
CREATE TRIGGER sidecar_comic_source_metadata_delete AFTER DELETE ON comic_source_metadata
BEGIN
 INSERT INTO comic_sidecars(comic_id,requested_at) SELECT id,strftime('%Y-%m-%dT%H:%M:%f000000Z','now') FROM comics WHERE id IN(SELECT OLD.comic_id)
 ON CONFLICT(comic_id) DO UPDATE SET revision=revision+1,requested_at=excluded.requested_at;
END;

CREATE TRIGGER sidecar_media_observations_insert AFTER INSERT ON media_observations
BEGIN
 INSERT INTO comic_sidecars(comic_id,requested_at) SELECT id,strftime('%Y-%m-%dT%H:%M:%f000000Z','now') FROM comics WHERE id IN(SELECT ch.comic_id FROM media m JOIN chapters ch ON ch.id=m.chapter_id WHERE m.id=NEW.media_id)
 ON CONFLICT(comic_id) DO UPDATE SET revision=revision+1,requested_at=excluded.requested_at;
END;
CREATE TRIGGER sidecar_media_observations_update AFTER UPDATE OF media_id,source_sha256,status ON media_observations
WHEN OLD.media_id IS NOT NEW.media_id
 OR OLD.source_sha256 IS NOT NEW.source_sha256
 OR OLD.status IS NOT NEW.status
BEGIN
 INSERT INTO comic_sidecars(comic_id,requested_at) SELECT id,strftime('%Y-%m-%dT%H:%M:%f000000Z','now') FROM comics WHERE id IN(SELECT ch.comic_id FROM media m JOIN chapters ch ON ch.id=m.chapter_id WHERE m.id=OLD.media_id)
 ON CONFLICT(comic_id) DO UPDATE SET revision=revision+1,requested_at=excluded.requested_at;
 INSERT INTO comic_sidecars(comic_id,requested_at) SELECT id,strftime('%Y-%m-%dT%H:%M:%f000000Z','now') FROM comics WHERE id IN(SELECT ch.comic_id FROM media m JOIN chapters ch ON ch.id=m.chapter_id WHERE m.id=NEW.media_id) AND id NOT IN(SELECT ch.comic_id FROM media m JOIN chapters ch ON ch.id=m.chapter_id WHERE m.id=OLD.media_id)
 ON CONFLICT(comic_id) DO UPDATE SET revision=revision+1,requested_at=excluded.requested_at;
END;
CREATE TRIGGER sidecar_media_observations_delete AFTER DELETE ON media_observations
BEGIN
 INSERT INTO comic_sidecars(comic_id,requested_at) SELECT id,strftime('%Y-%m-%dT%H:%M:%f000000Z','now') FROM comics WHERE id IN(SELECT ch.comic_id FROM media m JOIN chapters ch ON ch.id=m.chapter_id WHERE m.id=OLD.media_id)
 ON CONFLICT(comic_id) DO UPDATE SET revision=revision+1,requested_at=excluded.requested_at;
END;

CREATE TRIGGER sidecar_tags_rename AFTER UPDATE OF name,version ON tags
WHEN OLD.name IS NOT NEW.name
 OR OLD.version IS NOT NEW.version
BEGIN
 INSERT INTO comic_sidecars(comic_id,requested_at) SELECT id,strftime('%Y-%m-%dT%H:%M:%f000000Z','now') FROM comics WHERE id IN(SELECT comic_id FROM comic_tags WHERE tag_id=NEW.id)
 ON CONFLICT(comic_id) DO UPDATE SET revision=revision+1,requested_at=excluded.requested_at;
END;
CREATE TRIGGER sidecar_categories_rename AFTER UPDATE OF name,version ON categories
WHEN OLD.name IS NOT NEW.name
 OR OLD.version IS NOT NEW.version
BEGIN
 INSERT INTO comic_sidecars(comic_id,requested_at) SELECT id,strftime('%Y-%m-%dT%H:%M:%f000000Z','now') FROM comics WHERE id IN(SELECT id FROM comics WHERE category_id=NEW.id)
 ON CONFLICT(comic_id) DO UPDATE SET revision=revision+1,requested_at=excluded.requested_at;
END;
