CREATE TABLE export_task (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    comic_id    BIGINT      NOT NULL,
    status      VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    progress    SMALLINT    NOT NULL DEFAULT 0,
    output_root VARCHAR(20),
    output_path VARCHAR(500),
    output_size BIGINT      NOT NULL DEFAULT 0,
    error_msg   VARCHAR(500),
    created_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at DATETIME,
    INDEX idx_comic_id (comic_id),
    INDEX idx_status (status),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
