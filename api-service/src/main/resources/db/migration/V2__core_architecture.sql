-- ComicAtlas Core Architecture v1.2 DB Migration
-- 从当前 schema 迁移到 v1.2

USE comic_atlas;

-- 1. comic: storage_type → storage_policy
ALTER TABLE comic CHANGE COLUMN storage_type storage_policy VARCHAR(16) DEFAULT 'MANAGED';

-- 2. 新增 catalog 表
CREATE TABLE IF NOT EXISTS catalog (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    comic_id BIGINT NOT NULL,
    parent_id BIGINT DEFAULT NULL,
    title VARCHAR(255) NOT NULL,
    sort_order INT DEFAULT 0,
    path VARCHAR(512) DEFAULT NULL,
    level INT DEFAULT 0,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE INDEX uk_comic_parent_title (comic_id, parent_id, title),
    INDEX idx_comic_parent (comic_id, parent_id),
    INDEX idx_path (path),
    FOREIGN KEY (comic_id) REFERENCES comic(id) ON DELETE CASCADE,
    FOREIGN KEY (parent_id) REFERENCES catalog(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 3. chapter: 新增 catalog_id, sort_order, global_order, 调整唯一约束
ALTER TABLE chapter
    ADD COLUMN catalog_id    BIGINT DEFAULT NULL AFTER comic_id,
    ADD COLUMN sort_order    INT DEFAULT 0 AFTER chapter_no,
    ADD COLUMN global_order  INT DEFAULT 0 AFTER sort_order,
    DROP INDEX uk_comic_chapter,
    ADD UNIQUE INDEX uk_catalog_chapter (comic_id, catalog_id, chapter_no),
    ADD CONSTRAINT fk_chapter_catalog FOREIGN KEY (catalog_id) REFERENCES catalog(id) ON DELETE SET NULL,
    ADD INDEX idx_comic_global (comic_id, global_order);

-- 4. page: drop image_name, 新增 hq_root/hq_path/lq_root/lq_path
ALTER TABLE page
    DROP COLUMN image_name,
    ADD COLUMN hq_root VARCHAR(32) DEFAULT 'HQ' AFTER chapter_id,
    ADD COLUMN hq_path VARCHAR(512) AFTER hq_root,
    ADD COLUMN lq_root VARCHAR(32) DEFAULT NULL AFTER hq_path,
    ADD COLUMN lq_path VARCHAR(512) AFTER lq_root;

-- 5. import_task: 新增 source_type 和 source_path（修复 retry 硬编码问题）
ALTER TABLE import_task
    ADD COLUMN source_type VARCHAR(16) DEFAULT NULL AFTER source_ref,
    ADD COLUMN source_path VARCHAR(1024) DEFAULT NULL AFTER source_type;
