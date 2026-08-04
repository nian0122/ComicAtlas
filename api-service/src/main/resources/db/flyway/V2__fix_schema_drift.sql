-- ComicAtlas V2：修复 schema 与 entity 漂移
-- 所有 DDL 均设计为幂等：条件检查后执行，避免在 V1 已建列/索引的新库上报 Duplicate
-- 1. import_task: 条件补齐 batch_id 列
-- 2. comic: 条件补齐 category 列（Comic.java 实体仍在使用）
-- 3. 放宽 status 字段长度（VARCHAR(16) → VARCHAR(32)，防止未来枚举值溢出）—— 天然幂等
-- 4. 条件补齐 import_task.idx_batch_id 索引

-- import_task.batch_id（ImportTask.java entity 漂移）—— 条件添加
SET @col = (SELECT COUNT(*) FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'import_task' AND COLUMN_NAME = 'batch_id');
SET @sql = IF(@col = 0, 'ALTER TABLE import_task ADD COLUMN batch_id VARCHAR(64) DEFAULT NULL AFTER source_path', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- comic.category（Comic.java entity 漂移 — 旧 VARCHAR 列）—— 条件添加
SET @col = (SELECT COUNT(*) FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'comic' AND COLUMN_NAME = 'category');
SET @sql = IF(@col = 0, 'ALTER TABLE comic ADD COLUMN category VARCHAR(64) DEFAULT NULL AFTER category_id', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 放宽 comic.status
ALTER TABLE comic
    MODIFY COLUMN status VARCHAR(32) DEFAULT 'IMPORTING';

-- 放宽 import_task.status
ALTER TABLE import_task
    MODIFY COLUMN status VARCHAR(32) DEFAULT 'PENDING';

-- 放宽 page.hq_status
ALTER TABLE page
    MODIFY COLUMN hq_status VARCHAR(32) DEFAULT 'PENDING';

-- 放宽 page.lq_status（NOT_GENERATED 已 13 字符，VARCHAR(16) 过紧）
ALTER TABLE page
    MODIFY COLUMN lq_status VARCHAR(32) DEFAULT 'NOT_GENERATED';

-- 放宽 page.transcode_status
ALTER TABLE page
    MODIFY COLUMN transcode_status VARCHAR(32) NOT NULL DEFAULT 'NOT_NEEDED';

-- 补齐 import_task.idx_batch_id（批量导入查询用）—— 条件添加（检查 information_schema.STATISTICS）
SET @idx = (SELECT COUNT(*) FROM information_schema.STATISTICS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'import_task' AND INDEX_NAME = 'idx_batch_id');
SET @sql = IF(@idx = 0, 'ALTER TABLE import_task ADD INDEX idx_batch_id (batch_id)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
