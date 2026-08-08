package com.comicatlas.api.importer.entity;

import com.comicatlas.api.common.enums.RecoveryTaskStatus;
import lombok.Data;
import java.time.LocalDateTime;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;

/**
 * 存储恢复任务（从 HQ 文件重建数据库记录，逐本漫画执行恢复）。
 * <p>
 * 数据库实体（DO），禁止直接暴露给接口；对外使用 {@code dto/} 包对应 DTO/VO。
 */
@Data
@TableName("recovery_task")
public class RecoveryTask {
    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 关联 management_task.id 一对一扩展（V12 列） */
    private Long managementTaskId;
    /** 任务状态：PENDING/RUNNING/SUCCESS/FAILED */
    private RecoveryTaskStatus status;
    /** 待恢复漫画总数 */
    private Integer totalComics;
    /** 已成功恢复的漫画数 */
    private Integer recoveredComics;
    /** 跳过的漫画数 */
    private Integer skippedComics;
    /** 创建占位漫画数（无元数据时生成 RECOVERY_REQUIRED 占位行） */
    private Integer placeholderComics;
    /** 恢复失败的漫画数 */
    private Integer errorComics;
    /** 失败原因摘要 */
    private String errorMessage;
    /** 失败明细（TEXT） */
    private String errorDetails;
    /** 重试次数 */
    private Integer retryCount;
    /** 创建时间 */
    private LocalDateTime createdAt;
    /** 开始时间 */
    private LocalDateTime startedAt;
    /** 结束时间 */
    private LocalDateTime endedAt;
}
