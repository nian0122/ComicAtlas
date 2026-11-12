CREATE TABLE tasks_next (
 id TEXT PRIMARY KEY NOT NULL, type TEXT NOT NULL CHECK(type IN ('directory_import','archive_import','image_lq','storage_refresh','hq_release','directory_scan','archive_export','video_transcode','media_upload','metadata_refresh','lifecycle','lifecycle_audit','metadata_sync')),
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
CREATE UNIQUE INDEX tasks_active_target ON tasks(type,target_type,target_id) WHERE type IN ('image_lq','storage_refresh','hq_release','directory_scan','archive_export','video_transcode','media_upload','metadata_refresh','lifecycle','lifecycle_audit','metadata_sync') AND status IN ('queued','running','cancelling');


CREATE TABLE comic_sidecars (
 comic_id TEXT PRIMARY KEY NOT NULL REFERENCES comics(id) ON DELETE CASCADE,
 revision INTEGER NOT NULL DEFAULT 1 CHECK(revision>0),published_revision INTEGER NOT NULL DEFAULT 0 CHECK(published_revision>=0 AND published_revision<=revision),
 requested_at TEXT NOT NULL,published_at TEXT,last_task_id TEXT REFERENCES tasks(id)
);
CREATE INDEX comic_sidecars_dirty ON comic_sidecars(comic_id) WHERE revision>published_revision;
CREATE INDEX upload_sessions_active_comic ON upload_sessions(comic_id) WHERE status IN('active','submitted','activated');
CREATE TABLE metadata_sync_runs (
 task_id TEXT PRIMARY KEY NOT NULL REFERENCES tasks(id),comic_id TEXT NOT NULL,requested_revision INTEGER NOT NULL CHECK(requested_revision>0),
 observed_revision INTEGER CHECK(observed_revision IS NULL OR observed_revision>0),observed_at TEXT,sidecar_sha TEXT CHECK(sidecar_sha IS NULL OR length(sidecar_sha)=64),published INTEGER NOT NULL DEFAULT 0 CHECK(published IN(0,1))
);
INSERT INTO comic_sidecars(comic_id,requested_at) SELECT id,strftime('%Y-%m-%dT%H:%M:%f000000Z','now') FROM comics;

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
