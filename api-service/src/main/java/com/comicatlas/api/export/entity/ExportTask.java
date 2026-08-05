package com.comicatlas.api.export.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.comicatlas.api.common.enums.ExportTaskStatus;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("export_task")
public class ExportTask {
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 关联 management_task.id 一对一扩展（V12 列） */
    private Long managementTaskId;
    private Long comicId;
    private ExportTaskStatus status;      // PENDING, RUNNING, SUCCESS, FAILED
    private Integer progress;   // 0-100, -1 on FAILED
    private String outputRoot;
    private String outputPath;
    private Long outputSize;
    private String errorMsg;
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;

    public boolean isPending() { return status == ExportTaskStatus.PENDING; }
    public boolean isRunning() { return status == ExportTaskStatus.RUNNING; }
    public boolean isSuccess() { return status == ExportTaskStatus.SUCCESS; }
    public boolean isFailed() { return status == ExportTaskStatus.FAILED; }
}
