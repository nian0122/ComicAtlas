-- V6: 清理死字段 —— 2026-07-21 数据库审查后移除未被使用的字段
-- 这些字段在代码中已无引用，生产数据全部为 NULL / 默认值

-- 1. comic 表：删除 EXTERNAL 模式残留字段 + comic 级 LQ 状态（page 级已维护）
ALTER TABLE comic
    DROP COLUMN root_key,
    DROP COLUMN relative_path,
    DROP COLUMN lq_status;

-- 2. catalog 表：删除写入但从不读取的派生字段
--   path/level 由 parent_id + title 在内存递归计算即可，无需落库
ALTER TABLE catalog
    DROP COLUMN path,
    DROP COLUMN level;

-- 3. import_task 表：删除 HTTP/Torrent 下载预留字段（当前仅 ZIP/DIR 导入）
ALTER TABLE import_task
    DROP COLUMN current_page,
    DROP COLUMN downloaded_bytes;
