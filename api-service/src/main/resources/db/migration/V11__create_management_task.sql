-- ============================================================
-- V11: 统一管理任务体系
-- 创建 management_task（主表）+ management_task_item（逐目标项表）
-- 支持幂等键、目标冲突锁、批次聚合、重试 attempt 递增
-- ============================================================

-- 主表：保存通用身份/operation/target/aggregate/status/stage/progress/counts/idempotency/error/timestamps/version
CREATE TABLE management_task (
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
    INDEX idx_task_type (task_type),
    INDEX idx_status (status),
    INDEX idx_batch_id (batch_id),
    INDEX idx_created_at (created_at),
    UNIQUE INDEX uk_idempotency_key (idempotency_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='统一管理任务主表';

-- 目标项表：逐 comic/directory 追踪，支持目标冲突锁 + retry attempt 递增
CREATE TABLE management_task_item (
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
    INDEX idx_task_id (task_id),
    INDEX idx_target (target_type, target_id),
    INDEX idx_status (status),
    UNIQUE INDEX uk_active_target_lock (lock_key),
    CONSTRAINT fk_item_task FOREIGN KEY (task_id) REFERENCES management_task(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='统一管理任务目标项表';
