package com.comicatlas.api.management.entity;

import com.comicatlas.api.common.enums.ManagementTaskStatus;
import com.comicatlas.api.common.enums.TaskType;
import lombok.Data;

import java.time.LocalDateTime;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.Version;

/**
 * 统一管理任务目标项实体。
 * <p>
 * 逐 comic/directory 追踪，支持目标冲突锁（lock_key 唯一）和 retry attempt 递增。
 * 活跃时 lock_key 非空占用唯一约束，终态时设 NULL 释放。
 * <p>数据库实体（DO），禁止直接暴露给接口；对外使用 {@code dto/} 包对应 DTO/VO。
 */
@Data
@TableName("management_task_item")
public class ManagementTaskItem {

    @TableId(type = IdType.AUTO)
    /** 主键（自增） */
    private Long id;

    /** 关联 management_task.id */
    private Long taskId;

    /** 目标类型: COMIC/DIRECTORY */
    private String targetType;

    /** 目标ID */
    private Long targetId;

    /** 操作类型 */
    private TaskType operationType;

    /** 项状态 */
    private ManagementTaskStatus status;

    /** 第几次尝试 */
    private Integer attempt;

    /** 进度 0-100 */
    private Integer progress;

    /** 结果引用表类型: IMPORT_TASK/EXPORT_TASK 等 */
    private String resultRefType;

    /** 结果引用表 ID */
    private Long resultRefId;

    /** 错误信息 */
    private String errorMessage;

    /** 活跃锁键，完成时设 NULL 以释放唯一约束 */
    private String lockKey;

    /** 乐观锁版本号（@Version 手动管理） */
    @Version
    private Integer version;
    /** 创建时间 */
    private LocalDateTime createdAt;
    /** 更新时间 */
    private LocalDateTime updatedAt;
    /** 开始时间 */
    private LocalDateTime startedAt;
    /** 完成时间 */
    private LocalDateTime completedAt;

    // ======================== 便捷方法 ========================

    public boolean isTerminal() {
        return status != null && status.isTerminal();
    }

    /**
     * 构建 lock_key：targetType:targetId:operationType
     */
    public static String buildLockKey(String targetType, Long targetId, TaskType operationType) {
        return targetType + ":" + targetId + ":" + operationType.name();
    }
}
