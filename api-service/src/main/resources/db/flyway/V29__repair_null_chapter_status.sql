-- 历史数据可能在生命周期状态迁移前已存在 NULL，统一回填为可读的 READY 状态。
UPDATE chapter
SET status = 'READY'
WHERE status IS NULL;

-- 与实体契约保持一致，后续禁止再次写入 NULL。
ALTER TABLE chapter
    MODIFY COLUMN status VARCHAR(16) NOT NULL DEFAULT 'READY';
