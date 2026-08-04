-- V14: 章节 global_order 全书唯一约束
-- ============================================
-- 目的：支撑无冲突重排（两阶段更新），并强制全书阅读顺序连续唯一。
-- ============================================

-- 1. 数据修复：将每本漫画内的章节 global_order 重排为连续 1..N
--    （兼容历史数据中的重复/空洞，保证添加唯一约束前数据合法）
UPDATE chapter ch
INNER JOIN (
    SELECT id,
           ROW_NUMBER() OVER (PARTITION BY comic_id ORDER BY global_order, id) AS new_order
    FROM chapter
) x ON ch.id = x.id
SET ch.global_order = x.new_order;

-- 2. 添加 (comic_id, global_order) 唯一约束（条件添加，保证幂等）
SET @idx = (SELECT COUNT(*) FROM information_schema.STATISTICS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'chapter' AND INDEX_NAME = 'uk_comic_global');
SET @sql = IF(@idx = 0, 'ALTER TABLE chapter ADD UNIQUE INDEX uk_comic_global (comic_id, global_order)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
