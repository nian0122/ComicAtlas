-- page.file_size 曾同时承载 HQ 图片和视频源文件大小，统一迁移到 page.hq_size。
SET @page_hq_size = (SELECT COUNT(*) FROM information_schema.COLUMNS
                     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'page' AND COLUMN_NAME = 'hq_size');
SET @sql = IF(@page_hq_size = 0,
              'ALTER TABLE page ADD COLUMN hq_size BIGINT NOT NULL DEFAULT 0 AFTER transcode_status',
              'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @page_file_size = (SELECT COUNT(*) FROM information_schema.COLUMNS
                       WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'page' AND COLUMN_NAME = 'file_size');
SET @sql = IF(@page_file_size = 1,
              'UPDATE page SET hq_size = CASE WHEN hq_size = 0 THEN COALESCE(file_size, 0) ELSE hq_size END',
              'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF(@page_file_size = 1,
              'ALTER TABLE page DROP COLUMN file_size',
              'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- comic.file_size 与 comic.hq_size 重复，漫画级统计统一使用 hq_size。
SET @comic_file_size = (SELECT COUNT(*) FROM information_schema.COLUMNS
                         WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'comic' AND COLUMN_NAME = 'file_size');
SET @sql = IF(@comic_file_size = 1,
              'UPDATE comic SET hq_size = CASE WHEN hq_size = 0 THEN COALESCE(file_size, 0) ELSE hq_size END',
              'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF(@comic_file_size = 1,
              'ALTER TABLE comic DROP COLUMN file_size',
              'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
