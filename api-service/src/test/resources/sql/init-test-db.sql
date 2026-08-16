-- ============================================================
-- ComicAtlas 集成测试数据库初始化（合并 schema + 用户权限）
-- 结构来源：api-service/src/main/resources/db/schema.sql（空库 Flyway V1..V20 最终结构）
-- ============================================================

-- ======== Schema（与 api-service/src/main/resources/db/schema.sql 对齐）========

CREATE TABLE IF NOT EXISTS comic (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    title VARCHAR(255) NOT NULL,
    title_jpn VARCHAR(255),
    author VARCHAR(255),
    description TEXT,
    cover_path VARCHAR(512),
    total_pages INT DEFAULT 0,
    hq_size BIGINT DEFAULT 0,
    lq_size BIGINT DEFAULT 0,
    source_type VARCHAR(16),
    source_gallery_id VARCHAR(64),
    source_gallery_token VARCHAR(32),
    source_ref VARCHAR(512),
    storage_policy VARCHAR(16) DEFAULT 'MANAGED',
    status VARCHAR(32) DEFAULT 'IMPORTING',
    category_id BIGINT,
    category VARCHAR(64),
    deleted_at DATETIME,
    trashed_at DATETIME COMMENT '进入 TRASHED 的时间（7 天保留期起点）',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    version INT NOT NULL DEFAULT 1,
    UNIQUE INDEX idx_source (source_type, source_gallery_id),
    INDEX idx_status (status),
    INDEX idx_category_id (category_id),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS catalog (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    comic_id BIGINT NOT NULL,
    parent_id BIGINT DEFAULT NULL,
    title VARCHAR(255) NOT NULL,
    sort_order INT DEFAULT 0,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE INDEX uk_comic_parent_title (comic_id, parent_id, title),
    INDEX idx_comic_parent (comic_id, parent_id),
    FOREIGN KEY (comic_id) REFERENCES comic(id) ON DELETE CASCADE,
    FOREIGN KEY (parent_id) REFERENCES catalog(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS chapter (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    comic_id BIGINT NOT NULL,
    catalog_id BIGINT DEFAULT NULL,
    title VARCHAR(255),
    chapter_no VARCHAR(32) DEFAULT '1',
    page_count INT DEFAULT 0,
    sort_order INT DEFAULT 0,
    global_order INT DEFAULT 0,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    status VARCHAR(16) NOT NULL DEFAULT 'READY',
    trashed_at DATETIME COMMENT '进入 TRASHED 的时间（7 天保留期起点）',
    version INT NOT NULL DEFAULT 1,
    UNIQUE INDEX uk_chapter_comic_id (comic_id, id),
    UNIQUE INDEX uk_catalog_chapter (comic_id, catalog_id, chapter_no),
    UNIQUE INDEX uk_comic_global (comic_id, global_order),
    INDEX idx_comic_global (comic_id, global_order),
    FOREIGN KEY (comic_id) REFERENCES comic(id) ON DELETE CASCADE,
    FOREIGN KEY (catalog_id) REFERENCES catalog(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS page (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    chapter_id BIGINT NOT NULL,
    page_number INT NOT NULL,
    original_page_number INT COMMENT '回收前原页码，恢复时优先复用',
    hq_root VARCHAR(32) DEFAULT 'HQ',
    hq_path VARCHAR(512),
    lq_root VARCHAR(32) DEFAULT NULL,
    lq_path VARCHAR(512),
    hq_status VARCHAR(32) DEFAULT 'PENDING',
    lq_status VARCHAR(32) DEFAULT 'NOT_GENERATED',
    transcode_status VARCHAR(32) NOT NULL DEFAULT 'NOT_NEEDED',
    lq_size BIGINT DEFAULT 0,
    width INT,
    height INT,
    hq_size BIGINT DEFAULT 0,
    media_type VARCHAR(32) NOT NULL DEFAULT 'IMAGE',
    duration DECIMAL(10,3) DEFAULT NULL,
    container VARCHAR(32) DEFAULT NULL,
    video_codec VARCHAR(32) DEFAULT NULL,
    audio_codec VARCHAR(32) DEFAULT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    status VARCHAR(16) NOT NULL DEFAULT 'READY',
    trashed_at DATETIME COMMENT '进入 TRASHED 的时间（7 天保留期起点）',
    version INT NOT NULL DEFAULT 1,
    UNIQUE INDEX uk_chapter_page (chapter_id, page_number),
    INDEX idx_media_type (media_type),
    FOREIGN KEY (chapter_id) REFERENCES chapter(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS tag (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(255) NOT NULL,
    type VARCHAR(32),
    UNIQUE INDEX idx_name_type (name, type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS comic_tag (
    comic_id BIGINT NOT NULL,
    tag_id BIGINT NOT NULL,
    PRIMARY KEY (comic_id, tag_id),
    FOREIGN KEY (comic_id) REFERENCES comic(id) ON DELETE CASCADE,
    FOREIGN KEY (tag_id) REFERENCES tag(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS category (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(64) NOT NULL UNIQUE,
    sort_order INT DEFAULT 0,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS import_task (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    management_task_id BIGINT COMMENT '关联 management_task.id 一对一扩展',
    comic_id BIGINT,
    source_ref VARCHAR(512),
    source_type VARCHAR(16) DEFAULT NULL,
    source_path VARCHAR(1024) DEFAULT NULL,
    batch_id VARCHAR(64),
    status VARCHAR(32) DEFAULT 'PENDING',
    progress INT DEFAULT 0,
    total_pages INT,
    downloaded_pages INT DEFAULT 0,
    download_method VARCHAR(32) DEFAULT 'HTTP',
    download_speed BIGINT DEFAULT 0,
    eta_seconds INT DEFAULT 0,
    error_message VARCHAR(1024),
    retry_count INT DEFAULT 0,
    start_time DATETIME,
    end_time DATETIME,
    duration_ms BIGINT,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_status (status),
    INDEX idx_batch_id (batch_id),
    FOREIGN KEY (comic_id) REFERENCES comic(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS recovery_task (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    management_task_id BIGINT COMMENT '关联 management_task.id 一对一扩展',
    status VARCHAR(32) NOT NULL DEFAULT 'QUEUED',
    total_comics INT DEFAULT 0,
    recovered_comics INT DEFAULT 0,
    skipped_comics INT DEFAULT 0,
    placeholder_comics INT DEFAULT 0,
    error_comics INT DEFAULT 0,
    error_message TEXT,
    error_details TEXT,
    retry_count INT DEFAULT 0,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    started_at DATETIME,
    ended_at DATETIME,
    INDEX idx_status (status),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS directory_scan_task (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    management_task_id BIGINT COMMENT '关联 management_task.id 一对一扩展',
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    directory_path VARCHAR(1024) NOT NULL,
    total_items INT DEFAULT 0,
    result_json MEDIUMTEXT,
    error_message TEXT,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    started_at DATETIME,
    ended_at DATETIME,
    INDEX idx_status (status),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS export_task (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    management_task_id BIGINT COMMENT '关联 management_task.id 一对一扩展',
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

CREATE TABLE IF NOT EXISTS reading_history (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    comic_id BIGINT NOT NULL,
    chapter_id BIGINT NOT NULL,
    page_number INT DEFAULT 1,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE INDEX uk_comic (comic_id),
    INDEX idx_history_comic_chapter (comic_id, chapter_id),
    FOREIGN KEY (comic_id) REFERENCES comic(id) ON DELETE CASCADE,
    FOREIGN KEY (chapter_id) REFERENCES chapter(id) ON DELETE CASCADE,
    CONSTRAINT fk_history_chapter_comic FOREIGN KEY (comic_id, chapter_id) REFERENCES chapter (comic_id, id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS outbox_message (
    event_id        VARCHAR(36)   NOT NULL COMMENT '事件 UUID（PK）',
    task_id         BIGINT        COMMENT '关联 management_task.id（可选）',
    item_id         BIGINT        COMMENT '关联 management_task_item.id（可选）',
    attempt         INT           NOT NULL DEFAULT 0 COMMENT 'task/item attempt 快照',
    exchange        VARCHAR(128)  NOT NULL COMMENT '目标 exchange',
    routing_key     VARCHAR(128)  NOT NULL COMMENT '目标 routing key',
    event_type      VARCHAR(128)  NOT NULL COMMENT 'ComicEvent.eventType',
    version         INT           NOT NULL DEFAULT 1 COMMENT 'ComicEvent.version()',
    payload         MEDIUMTEXT    NOT NULL COMMENT 'JSON 序列化的事件体',
    publish_attempts INT          NOT NULL DEFAULT 0 COMMENT 'relay 发布尝试次数',
    status          VARCHAR(16)   NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/PUBLISHED/FAILED',
    available_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最早可发布时间',
    published_at    DATETIME      COMMENT '确认发布时间',
    last_error      VARCHAR(2048) COMMENT '最后一次发布错误',
    created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (event_id),
    INDEX idx_om_status_available (status, available_at),
    INDEX idx_om_task_id (task_id),
    INDEX idx_om_published_at (published_at),
    INDEX idx_om_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务Outbox消息表';

CREATE TABLE IF NOT EXISTS inbox_receipt (
    event_id     VARCHAR(36)  NOT NULL COMMENT '事件 UUID（PK）',
    payload_hash VARCHAR(64)  NOT NULL COMMENT 'payload SHA-256',
    task_id      BIGINT       COMMENT '关联 management_task.id（可选）',
    item_id      BIGINT       COMMENT '关联 management_task_item.id（可选）',
    attempt      INT          NOT NULL DEFAULT 0 COMMENT 'task/item attempt 快照',
    processed_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '处理时间',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (event_id),
    INDEX idx_ir_processed_at (processed_at),
    INDEX idx_ir_task_id (task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='结果Inbox收据表';

CREATE TABLE IF NOT EXISTS management_task (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_type       VARCHAR(32)   NOT NULL COMMENT '任务类型: IMPORT/RECOVERY/EXPORT/DIRECTORY_SCAN',
    operation       VARCHAR(64)   NOT NULL COMMENT '操作描述',
    target_type     VARCHAR(32)   COMMENT '目标类型: COMIC/DIRECTORY/SYSTEM',
    batch_id        VARCHAR(36)   COMMENT '批次ID，关联 import_task.batch_id 等',
    is_batch        TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '是否批量任务',
    status          VARCHAR(32)   NOT NULL DEFAULT 'QUEUED' COMMENT '任务状态: QUEUED/RUNNING/CANCELLING/CANCELLED/SUCCEEDED/PARTIALLY_SUCCEEDED/FAILED',
    stage           VARCHAR(64)   COMMENT '当前阶段描述',
    progress        INT           DEFAULT 0 COMMENT '聚合进度 0-100',
    total_count     INT           DEFAULT 0 COMMENT '总目标数',
    success_count   INT           DEFAULT 0 COMMENT '成功项数',
    failure_count   INT           DEFAULT 0 COMMENT '失败项数',
    cancelled_count INT           DEFAULT 0 COMMENT '取消项数',
    idempotency_key VARCHAR(128)  COMMENT '幂等键，同键同payload返回原任务（唯一）',
    idempotency_payload_hash VARCHAR(64) COMMENT '幂等负载 SHA-256',
    error_message   VARCHAR(4096) COMMENT '错误摘要',
    error_detail    TEXT          COMMENT '错误详情 JSON',
    attempt         INT           NOT NULL DEFAULT 1 COMMENT '当前第几次尝试',
    version         INT           NOT NULL DEFAULT 0 COMMENT '@Version 乐观锁',
    created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    started_at      DATETIME      COMMENT '开始时间',
    completed_at    DATETIME      COMMENT '完成时间',
    INDEX idx_mt_task_type (task_type),
    INDEX idx_mt_status (status),
    INDEX idx_mt_batch_id (batch_id),
    INDEX idx_mt_created_at (created_at),
    UNIQUE INDEX uk_mt_idempotency_key (idempotency_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='统一管理任务主表';

CREATE TABLE IF NOT EXISTS management_task_item (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id         BIGINT       NOT NULL COMMENT '关联 management_task.id',
    target_type     VARCHAR(32)  NOT NULL COMMENT '目标类型: COMIC/DIRECTORY',
    target_id       BIGINT       NOT NULL COMMENT '目标ID',
    operation_type  VARCHAR(32)  NOT NULL COMMENT '操作类型: IMPORT/RECOVERY/EXPORT/DIRECTORY_SCAN',
    status          VARCHAR(32)  NOT NULL DEFAULT 'QUEUED' COMMENT '项状态',
    attempt         INT          NOT NULL DEFAULT 1 COMMENT '第几次尝试',
    progress        INT          DEFAULT 0 COMMENT '进度 0-100',
    result_ref_type VARCHAR(32)  COMMENT '结果引用表类型: IMPORT_TASK/EXPORT_TASK 等',
    result_ref_id   BIGINT       COMMENT '结果引用表 ID',
    error_message   VARCHAR(4096) COMMENT '错误信息',
    lock_key        VARCHAR(128) COMMENT '活跃锁键 targetType:targetId:operationType，完成时设NULL释放唯一约束',
    version         INT          NOT NULL DEFAULT 0 COMMENT '@Version 乐观锁',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    started_at      DATETIME     COMMENT '开始时间',
    completed_at    DATETIME     COMMENT '完成时间',
    INDEX idx_mti_task_id (task_id),
    INDEX idx_mti_target (target_type, target_id),
    INDEX idx_mti_status (status),
    UNIQUE INDEX uk_mti_active_target_lock (lock_key),
    CONSTRAINT fk_mti_task FOREIGN KEY (task_id) REFERENCES management_task(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='统一管理任务目标项表';

CREATE TABLE IF NOT EXISTS upload_session (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id       VARCHAR(64)  NOT NULL COMMENT '对外 opaque 会话 ID（UUID）',
    comic_id         BIGINT       NOT NULL COMMENT '目标漫画',
    chapter_id       BIGINT       NOT NULL COMMENT '目标章节',
    replace_media_id BIGINT       NULL     COMMENT '替换目标媒体 ID（replace 流程）',
    status           VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/COMPLETED/CANCELLED/EXPIRED/FAILED',
    total_bytes      BIGINT       NOT NULL DEFAULT 0 COMMENT '会话总字节数',
    total_files      INT          NOT NULL DEFAULT 0 COMMENT '文件数',
    expires_at       DATETIME     NOT NULL COMMENT '未完成过期时间',
    completed_at     DATETIME     NULL     COMMENT 'complete 时间',
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE INDEX uk_upload_session_id (session_id),
    INDEX idx_us_comic (comic_id),
    INDEX idx_us_status_expires (status, expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='分片上传会话';

CREATE TABLE IF NOT EXISTS upload_file (
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

CREATE TABLE IF NOT EXISTS trash_manifest (
    task_id        BIGINT       NOT NULL COMMENT '管理任务 ID（唯一）',
    target_type    VARCHAR(32)  NOT NULL COMMENT '目标类型：COMIC/CHAPTER/MEDIA',
    target_id      BIGINT       NOT NULL COMMENT '目标实体 ID',
    manifest_json  TEXT         NOT NULL COMMENT '不可变 TRASH 清单 JSON（TrashManifestDTO）',
    created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (task_id),
    INDEX idx_target (target_type, target_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='TRASH 资产清单（API 写 DB，Worker 只读 DB + 操作文件）';

-- ======== 测试用户权限 ========

CREATE USER IF NOT EXISTS 'api_user'@'%' IDENTIFIED BY 'api_test_pass';
GRANT ALL PRIVILEGES ON comic_atlas_test.* TO 'api_user'@'%';

CREATE USER IF NOT EXISTS 'worker_user'@'%' IDENTIFIED BY 'worker_test_pass';
GRANT SELECT ON comic_atlas_test.* TO 'worker_user'@'%';

FLUSH PRIVILEGES;
