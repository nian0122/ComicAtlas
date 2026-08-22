-- ComicAtlas V24：导出格式（ZIP/CBZ），默认保持历史 ZIP 行为
SET @column_exists = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'export_task' AND COLUMN_NAME = 'format'
);
SET @sql = IF(@column_exists = 0,
    'ALTER TABLE export_task ADD COLUMN format VARCHAR(8) NOT NULL DEFAULT ''ZIP'' AFTER comic_id',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
