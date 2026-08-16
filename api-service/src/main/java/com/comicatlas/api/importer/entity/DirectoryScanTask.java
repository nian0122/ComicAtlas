package com.comicatlas.api.importer.entity;

import com.comicatlas.api.common.enums.DirectoryScanTaskStatus;
import lombok.Data;
import java.time.LocalDateTime;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;

/**
 * 目录扫描任务（异步扫描源目录并回写扫描结果 JSON）。
 * <p>
 * 数据库实体（DO），禁止直接暴露给接口；对外使用 {@code dto/} 包对应 DTO/VO。
 */
@Data
@TableName("directory_scan_task")
public class DirectoryScanTask {
    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 关联 management_task.id 一对一扩展（V12 列） */
    private Long managementTaskId;
    /** 任务状态：PENDING/RUNNING/SUCCESS/FAILED */
    private DirectoryScanTaskStatus status;
    /** 待扫描的源目录路径 */
    private String directoryPath;
    /** 扫描到的条目总数 */
    private Integer totalItems;
    /** 扫描结果 JSON（MEDIUMTEXT） */
    private String resultJson;
    /** 失败原因 */
    private String errorMessage;
    /** 重试次数 */
    private Integer retryCount;
    /** 创建时间 */
    private LocalDateTime createdAt;
    /** 开始时间 */
    private LocalDateTime startedAt;
    /** 结束时间 */
    private LocalDateTime endedAt;
}
