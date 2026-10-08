-- 最近成功的缩略图目录容量；扫描失败或重启不丢失历史结果。
CREATE TABLE storage_capacity_snapshot (
    root_key VARCHAR(32) PRIMARY KEY,
    root_fingerprint VARCHAR(64) NULL,
    total_bytes BIGINT NOT NULL DEFAULT 0,
    file_count BIGINT NOT NULL DEFAULT 0,
    scanned_at DATETIME(6) NULL,
    attempted_at DATETIME(6) NULL,
    requested_at DATETIME(6) NOT NULL,
    requested_version BIGINT NOT NULL DEFAULT 1,
    completed_version BIGINT NOT NULL DEFAULT 0,
    refresh_status VARCHAR(16) NOT NULL DEFAULT 'PENDING'
);
