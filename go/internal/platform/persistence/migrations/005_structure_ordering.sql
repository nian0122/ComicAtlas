ALTER TABLE comics ADD COLUMN structure_version INTEGER NOT NULL DEFAULT 1 CHECK(structure_version > 0);
ALTER TABLE chapters ADD COLUMN sort_order INTEGER NOT NULL DEFAULT 0;
ALTER TABLE chapters ADD COLUMN media_version INTEGER NOT NULL DEFAULT 1 CHECK(media_version > 0);
-- 原目录顺序转为混排顺序：每个目录由首个后代章节定位，维持原阅读序列。
WITH RECURSIVE descendants(root_id,id) AS (
    SELECT id,id FROM catalogs
    UNION SELECT d.root_id,c.id FROM catalogs c JOIN descendants d ON c.parent_id=d.id
), firsts AS (
    SELECT d.root_id,MIN(ch.global_order) AS position
    FROM descendants d JOIN chapters ch ON ch.catalog_id=d.id GROUP BY d.root_id
)
UPDATE catalogs SET sort_order=2*COALESCE(
    (SELECT position FROM firsts WHERE root_id=catalogs.id),
    COALESCE((SELECT MAX(global_order) FROM chapters WHERE comic_id=catalogs.comic_id),0)+sort_order+1
);
UPDATE chapters SET sort_order=2*global_order+1;
CREATE INDEX catalogs_parent_order ON catalogs(comic_id,parent_id,sort_order,id);
CREATE INDEX chapters_catalog_order ON chapters(comic_id,catalog_id,sort_order,id);
CREATE TRIGGER catalogs_parent_insert BEFORE INSERT ON catalogs WHEN NEW.parent_id IS NOT NULL
BEGIN
    SELECT RAISE(ABORT,'目录父节点归属无效') WHERE NOT EXISTS(
        SELECT 1 FROM catalogs p WHERE p.id=NEW.parent_id AND p.comic_id=NEW.comic_id AND p.id<>NEW.id
    );
END;
CREATE TRIGGER catalogs_parent_update BEFORE UPDATE OF parent_id,comic_id ON catalogs WHEN NEW.parent_id IS NOT NULL
BEGIN
    SELECT RAISE(ABORT,'目录父节点归属无效') WHERE NOT EXISTS(
        SELECT 1 FROM catalogs p WHERE p.id=NEW.parent_id AND p.comic_id=NEW.comic_id AND p.id<>NEW.id
    );
END;
CREATE TRIGGER chapters_catalog_insert BEFORE INSERT ON chapters WHEN NEW.catalog_id IS NOT NULL
BEGIN
    SELECT RAISE(ABORT,'章节目录归属无效') WHERE NOT EXISTS(
        SELECT 1 FROM catalogs p WHERE p.id=NEW.catalog_id AND p.comic_id=NEW.comic_id
    );
END;
CREATE TRIGGER chapters_catalog_update BEFORE UPDATE OF catalog_id,comic_id ON chapters WHEN NEW.catalog_id IS NOT NULL
BEGIN
    SELECT RAISE(ABORT,'章节目录归属无效') WHERE NOT EXISTS(
        SELECT 1 FROM catalogs p WHERE p.id=NEW.catalog_id AND p.comic_id=NEW.comic_id
    );
END;
