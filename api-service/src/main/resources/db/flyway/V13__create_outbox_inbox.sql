-- ============================================================
-- V13: 事务 Outbox + 结果 Inbox 表
-- 保证 DB commit 与 MQ 发布最终一致
-- ============================================================

-- 事务 Outbox 消息表：业务写 + outbox 同事务，relay 负责异步发布
CREATE TABLE outbox_message (
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
    INDEX idx_om_status_available (status, available_at),
    INDEX idx_om_task_id (task_id),
    INDEX idx_om_published_at (published_at),
    INDEX idx_om_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务Outbox消息表';

-- 结果 Inbox 收据表：幂等去重，eventId PK 保证恰好一次
CREATE TABLE inbox_receipt (
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
