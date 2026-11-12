CREATE TABLE categories (
    id TEXT PRIMARY KEY NOT NULL,
    name TEXT NOT NULL,
    name_key TEXT NOT NULL UNIQUE,
    version INTEGER NOT NULL DEFAULT 1 CHECK(version > 0)
);
CREATE INDEX categories_name_order ON categories(name COLLATE COMIC_NATURAL,id);
CREATE TABLE tags (
    id TEXT PRIMARY KEY NOT NULL,
    name TEXT NOT NULL,
    name_key TEXT NOT NULL UNIQUE,
    version INTEGER NOT NULL DEFAULT 1 CHECK(version > 0)
);
CREATE INDEX tags_name_order ON tags(name COLLATE COMIC_NATURAL,id);
ALTER TABLE comics ADD COLUMN category_id TEXT REFERENCES categories(id);
ALTER TABLE comics ADD COLUMN category_name TEXT NOT NULL DEFAULT '';
ALTER TABLE comics ADD COLUMN version INTEGER NOT NULL DEFAULT 1 CHECK(version > 0);
CREATE INDEX comics_category ON comics(category_id,id);
CREATE TABLE comic_tags (
    comic_id TEXT NOT NULL REFERENCES comics(id) ON DELETE CASCADE,
    tag_id TEXT NOT NULL REFERENCES tags(id),
    source TEXT NOT NULL CHECK(source IN ('manual','comicinfo','auto')),
    PRIMARY KEY(comic_id,tag_id)
);
CREATE INDEX comic_tags_reverse ON comic_tags(tag_id,comic_id);
-- 固定九位小数，避免可变精度 RFC3339 的字典序与时间先后不一致。
UPDATE reading_progress SET updated_at=substr(updated_at,1,19)||'.'||
    substr((CASE WHEN substr(updated_at,20,1)='.' THEN substr(updated_at,21,length(updated_at)-21) ELSE '' END)||'000000000',1,9)||'Z';
CREATE INDEX reading_progress_recent ON reading_progress(updated_at DESC,comic_id);
CREATE TABLE comic_reactions (
    target_id TEXT PRIMARY KEY NOT NULL REFERENCES comics(id) ON DELETE CASCADE,
    reaction TEXT NOT NULL CHECK(reaction IN ('like','dislike')),
    updated_at TEXT NOT NULL
);
CREATE TABLE chapter_reactions (
    target_id TEXT PRIMARY KEY NOT NULL REFERENCES chapters(id) ON DELETE CASCADE,
    reaction TEXT NOT NULL CHECK(reaction IN ('like','dislike')),
    updated_at TEXT NOT NULL
);
CREATE TABLE media_reactions (
    target_id TEXT PRIMARY KEY NOT NULL REFERENCES media(id) ON DELETE CASCADE,
    reaction TEXT NOT NULL CHECK(reaction IN ('like','dislike')),
    updated_at TEXT NOT NULL
);
CREATE INDEX comic_reactions_recent ON comic_reactions(reaction,updated_at DESC,target_id);
CREATE INDEX chapter_reactions_recent ON chapter_reactions(reaction,updated_at DESC,target_id);
CREATE INDEX media_reactions_recent ON media_reactions(reaction,updated_at DESC,target_id);
CREATE VIEW content_reactions AS
    SELECT 'comic' AS target_type,target_id,reaction,updated_at FROM comic_reactions
    UNION ALL SELECT 'chapter',target_id,reaction,updated_at FROM chapter_reactions
    UNION ALL SELECT 'media',target_id,reaction,updated_at FROM media_reactions;
CREATE TABLE reader_settings (
    singleton INTEGER PRIMARY KEY CHECK(singleton=1),
    quality TEXT NOT NULL CHECK(quality IN ('auto','hq','lq')),
    fit TEXT NOT NULL CHECK(fit IN ('auto','width','height','original')),
    direction TEXT NOT NULL CHECK(direction IN ('vertical','horizontal','ltr','rtl')),
    version INTEGER NOT NULL CHECK(version > 0)
);
INSERT INTO reader_settings VALUES(1,'auto','auto','vertical',1);
