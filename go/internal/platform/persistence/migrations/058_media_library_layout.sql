CREATE TABLE comic_cover_selection(
 comic_id TEXT PRIMARY KEY REFERENCES comics(id) ON DELETE CASCADE,
 media_id TEXT NOT NULL REFERENCES media(id) ON DELETE CASCADE,
 version INTEGER NOT NULL CHECK(version>0)
);
-- 封面归漫画所有；media_id 是生成来源证据，删除来源不级联丢失封面登记。
CREATE TABLE comic_covers(
 comic_id TEXT PRIMARY KEY REFERENCES comics(id) ON DELETE CASCADE,
 media_id TEXT NOT NULL,
 source_key TEXT NOT NULL,relative_path TEXT NOT NULL UNIQUE,sha256 TEXT NOT NULL,
 size INTEGER NOT NULL CHECK(size>0),width INTEGER NOT NULL CHECK(width>0),height INTEGER NOT NULL CHECK(height>0)
);
-- path 是登记的根内访问键，非实际存在性声明；隐藏文件的位置以生命周期清单为准。
CREATE VIEW media_file_ownership AS
 WITH owned_media AS (
 SELECT m.*,ch.comic_id,CASE WHEN c.lifecycle_state<>'active' THEN c.lifecycle_state WHEN ch.lifecycle_state<>'active' THEN ch.lifecycle_state ELSE m.lifecycle_state END owner_state
 FROM media m JOIN chapters ch ON ch.id=m.chapter_id JOIN comics c ON c.id=ch.comic_id
 )
 SELECT comic_id,chapter_id,id media_id,'original' role,relative_path path,size,sha256,owner_state state FROM owned_media WHERE hq_state IN('available','releasing')
 UNION ALL SELECT m.comic_id,m.chapter_id,m.id,'lq',l.relative_path,l.size,l.sha256,m.owner_state FROM image_lq l JOIN owned_media m ON m.id=l.media_id
 UNION ALL SELECT m.comic_id,m.chapter_id,m.id,'thumbnail',t.relative_path,t.size,t.sha256,m.owner_state FROM media_thumbnails t JOIN owned_media m ON m.id=t.media_id
 UNION ALL SELECT c.comic_id,m.chapter_id,c.media_id,'cover',c.relative_path,c.size,c.sha256,b.lifecycle_state FROM comic_covers c JOIN comics b ON b.id=c.comic_id LEFT JOIN owned_media m ON m.id=c.media_id
 UNION ALL SELECT s.comic_id,s.chapter_id,s.replace_media_id,'retired_'||r.kind,'retired/uploads/'||s.id||'/'||r.kind||'.bin',r.size,r.sha256,r.status FROM upload_retired r JOIN upload_sessions s ON s.task_id=r.task_id WHERE r.status='moved'
 UNION ALL SELECT s.comic_id,s.chapter_id,f.media_id,'upload_pending',f.target_path,f.size,f.sha256,f.publish_state FROM upload_files f JOIN upload_sessions s ON s.id=f.session_id WHERE f.target_path IS NOT NULL AND s.status IN('submitted','cancelled','expired') AND s.cleaned=0
 UNION ALL SELECT id,NULL,NULL,'metadata','metadata/'||id||'.json',NULL,NULL,lifecycle_state FROM comics;

CREATE TRIGGER library_layout_default_cover AFTER INSERT ON media WHEN NEW.media_type='image' AND NEW.lifecycle_state='active' BEGIN
 INSERT INTO comic_cover_selection(comic_id,media_id,version) SELECT comic_id,NEW.id,1 FROM chapters WHERE id=NEW.chapter_id ON CONFLICT(comic_id) DO NOTHING;
END;
CREATE TRIGGER library_layout_comic_cover_selection_insert AFTER INSERT ON comic_cover_selection BEGIN
 UPDATE comic_change_clock SET revision=revision+1 WHERE singleton=1;
 UPDATE comic_change_versions SET revision=(SELECT revision FROM comic_change_clock WHERE singleton=1) WHERE comic_id=NEW.comic_id;
 INSERT INTO comic_sidecars(comic_id,requested_at) SELECT id,strftime('%Y-%m-%dT%H:%M:%f000000Z','now') FROM comics WHERE id=NEW.comic_id ON CONFLICT(comic_id) DO UPDATE SET revision=revision+1,requested_at=excluded.requested_at;
END;
CREATE TRIGGER library_layout_comic_cover_selection_update AFTER UPDATE ON comic_cover_selection BEGIN
 UPDATE comic_change_clock SET revision=revision+1 WHERE singleton=1;
 UPDATE comic_change_versions SET revision=(SELECT revision FROM comic_change_clock WHERE singleton=1) WHERE comic_id=NEW.comic_id;
 INSERT INTO comic_sidecars(comic_id,requested_at) SELECT id,strftime('%Y-%m-%dT%H:%M:%f000000Z','now') FROM comics WHERE id=NEW.comic_id ON CONFLICT(comic_id) DO UPDATE SET revision=revision+1,requested_at=excluded.requested_at;
END;
CREATE TRIGGER library_layout_comic_cover_selection_delete AFTER DELETE ON comic_cover_selection BEGIN
 UPDATE comic_change_clock SET revision=revision+1 WHERE singleton=1;
 UPDATE comic_change_versions SET revision=(SELECT revision FROM comic_change_clock WHERE singleton=1) WHERE comic_id=OLD.comic_id;
 INSERT INTO comic_sidecars(comic_id,requested_at) SELECT id,strftime('%Y-%m-%dT%H:%M:%f000000Z','now') FROM comics WHERE id=OLD.comic_id ON CONFLICT(comic_id) DO UPDATE SET revision=revision+1,requested_at=excluded.requested_at;
END;
CREATE TRIGGER library_layout_comic_covers_insert AFTER INSERT ON comic_covers BEGIN
 UPDATE comic_change_clock SET revision=revision+1 WHERE singleton=1;
 UPDATE comic_change_versions SET revision=(SELECT revision FROM comic_change_clock WHERE singleton=1) WHERE comic_id=NEW.comic_id;
END;
CREATE TRIGGER library_layout_comic_covers_update AFTER UPDATE ON comic_covers BEGIN
 UPDATE comic_change_clock SET revision=revision+1 WHERE singleton=1;
 UPDATE comic_change_versions SET revision=(SELECT revision FROM comic_change_clock WHERE singleton=1) WHERE comic_id=NEW.comic_id;
END;
CREATE TRIGGER library_layout_comic_covers_delete AFTER DELETE ON comic_covers BEGIN
 UPDATE comic_change_clock SET revision=revision+1 WHERE singleton=1;
 UPDATE comic_change_versions SET revision=(SELECT revision FROM comic_change_clock WHERE singleton=1) WHERE comic_id=OLD.comic_id;
END;
