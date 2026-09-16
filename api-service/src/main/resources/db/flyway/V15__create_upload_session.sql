-- ============================================================
-- V15: 分片上传会话与媒体上传
-- upload_session（会话）+ upload_file（会话内文件，服务端生成文件名）
-- 预建 STAGING media rows 由 API 在 complete 时写入，此处仅表结构。
-- ============================================================

CREATE TABLE upload_session (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id       VARCHAR(64)  NOT NULL COMMENT '对外 opaque 会话 ID（UUID）',
    comic_id         BIGINT       NOT NULL COMMENT '目标漫画',
    chapter_id       BIGINT       NOT NULL COMMENT '目标章节',
    replace_media_id BIGINT       NULL     COMMENT '替换目标媒体 ID（replace 流程）',
    status           VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/COMPLETED/CANCELLED/EXPIRED/FAILED',
    total_bytes      BIGINT       NOT NULL DEFAULT 0 COMMENT '会话总字节数',
    total_files      INT          NOT NULL DEFAULT 0 COMMENT '文件数',
    expires_at       DATETIME     NOT NULL COMMENT '未完成过期时间',
    completed_at     DATETIME     NULL COMMENT 'complete 时间',
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE INDEX uk_upload_session_id (session_id),
    INDEX idx_us_comic (comic_id),
    INDEX idx_us_status_expires (status, expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='分片上传会话';

CREATE TABLE upload_file (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id      BIGINT       NOT NULL COMMENT '关联 upload_session.id',
    file_id         VARCHAR(64)  NOT NULL COMMENT '客户端 opaque 文件标识',
    original_name   VARCHAR(255) NOT NULL COMMENT '客户端文件名（仅展示，不用于拼路径）',
    content_type    VARCHAR(128) NOT NULL COMMENT '客户端声明 Content-Type',
    size_bytes      BIGINT       NOT NULL COMMENT '声明文件大小',
    sha256          VARCHAR(64)  NOT NULL COMMENT '声明文件总 SHA-256',
    storage_name    VARCHAR(255) NOT NULL COMMENT '服务端生成文件名 uuid.ext',
    received_bytes  BIGINT       NOT NULL DEFAULT 0 COMMENT '已接收最大末端字节',
    received_ranges TEXT         NULL     COMMENT '已接收区间串 0-65535;131072-196607',
    media_id        BIGINT       NULL     COMMENT 'complete 预建 STAGING media row id',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE INDEX uk_upload_file (session_id, file_id),
    INDEX idx_uf_media (media_id),
    CONSTRAINT fk_upload_file_session FOREIGN KEY (session_id) REFERENCES upload_session(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='上传会话内文件';
