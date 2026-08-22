package com.comicatlas.api.exporter.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.comicatlas.api.common.enums.ExportTaskStatus;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 导出任务（将漫画导出为 ZIP 产物）。
 * <p>
 * 数据库实体（DO），禁止直接暴露给接口；对外使用 {@code dto/} 包对应 DTO/VO。
 */
@Data
@TableName("export_task")
public class ExportTask {
    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 关联 management_task.id 一对一扩展（V12 列） */
    private Long managementTaskId;
    /** 被导出的漫画 ID */
    private Long comicId;
    /** 导出格式：ZIP（默认）或 CBZ。 */
    private String format;
    /** 任务状态：PENDING/RUNNING/SUCCESS/FAILED */
    private ExportTaskStatus status;      // PENDING, RUNNING, SUCCESS, FAILED
    /** 进度百分比 0-100，失败时为 -1 */
    private Integer progress;   // 0-100, -1 on FAILED
    /** 输出根路径（默认取配置的导出目录） */
    private String outputRoot;
    /** 输出文件相对路径（worker 回填，如 {标题}_{comicId}_{时间戳}.zip） */
    private String outputPath;
    /** 导出产物大小（字节） */
    private Long outputSize;
    /** 失败原因 */
    private String errorMsg;
    /** 创建时间 */
    private LocalDateTime createdAt;
    /** 完成时间 */
    private LocalDateTime completedAt;

    public boolean isPending() { return status == ExportTaskStatus.PENDING; }
    public boolean isRunning() { return status == ExportTaskStatus.RUNNING; }
    public boolean isSuccess() { return status == ExportTaskStatus.SUCCESS; }
    public boolean isFailed() { return status == ExportTaskStatus.FAILED; }
}
