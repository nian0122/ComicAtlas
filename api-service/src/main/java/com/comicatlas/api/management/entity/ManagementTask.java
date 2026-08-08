package com.comicatlas.api.management.entity;

import com.comicatlas.api.common.enums.ManagementTaskStatus;
import com.comicatlas.api.common.enums.TaskType;
import lombok.Data;

import java.time.LocalDateTime;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.Version;

/**
 * 统一管理任务主表实体。
 * <p>
 * 保存通用身份/operation/target/aggregate/status/stage/progress/counts/
 * idempotency fingerprint/error/timestamps/version，不塞具体业务 payload。
 */
@Data
@TableName("management_task")
public class ManagementTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 任务类型 */
    private TaskType taskType;

    /** 操作描述 */
    private String operation;

    /** 目标类型: COMIC/DIRECTORY/SYSTEM */
    private String targetType;

    /** 批次ID */
    private String batchId;

    /** 是否批量任务（DB 列 is_batch，内部名 batch） */
    @TableField("is_batch")
    private Boolean batch;

    /** 任务状态 */
    private ManagementTaskStatus status;

    /** 当前阶段 */
    private String stage;

    /** 聚合进度 0-100 */
    private Integer progress;

    /** 总目标数 */
    private Integer totalCount;

    /** 成功项数 */
    private Integer successCount;

    /** 失败项数 */
    private Integer failureCount;

    /** 取消项数 */
    private Integer cancelledCount;

    /** 幂等键（唯一） */
    private String idempotencyKey;

    /** 幂等负载 SHA-256 */
    private String idempotencyPayloadHash;

    /** 错误摘要 */
    private String errorMessage;

    /** 错误详情 JSON */
    private String errorDetail;

    /** 当前第几次尝试 */
    private Integer attempt;

    /** @Version 乐观锁 */
    @Version
    private Integer version;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;

    // ======================== 便捷方法 ========================

    public boolean isTerminal() {
        return status != null && status.isTerminal();
    }

    public boolean isProcessing() {
        return status != null && status.isProcessing();
    }
}
