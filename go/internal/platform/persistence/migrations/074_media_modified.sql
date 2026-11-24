-- 修改时间由导入和媒体核对记录。旧记录首次核对时建立基线。
ALTER TABLE media ADD COLUMN modified_ns INTEGER NOT NULL DEFAULT 0;
