CREATE TABLE media_thumbnails (
 media_id TEXT PRIMARY KEY NOT NULL REFERENCES media(id) ON DELETE CASCADE,
 source_key TEXT NOT NULL CHECK(length(source_key)=64),
 relative_path TEXT NOT NULL UNIQUE,sha256 TEXT NOT NULL CHECK(length(sha256)=64),
 size INTEGER NOT NULL CHECK(size BETWEEN 1 AND 2097152),
 width INTEGER NOT NULL CHECK(width BETWEEN 1 AND 512),height INTEGER NOT NULL CHECK(height BETWEEN 1 AND 512)
);
CREATE TABLE thumbnail_garbage (relative_path TEXT PRIMARY KEY NOT NULL);
CREATE TRIGGER thumbnails_deleted AFTER DELETE ON media_thumbnails BEGIN
 INSERT OR IGNORE INTO thumbnail_garbage VALUES(OLD.relative_path);
END;
