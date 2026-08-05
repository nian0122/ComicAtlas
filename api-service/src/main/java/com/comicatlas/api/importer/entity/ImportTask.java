package com.comicatlas.api.importer.entity;

import com.comicatlas.api.common.enums.ImportTaskStatus;
import com.comicatlas.api.common.enums.SourceType;
import lombok.Data;
import java.time.LocalDateTime;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;

@Data
@TableName("import_task")
public class ImportTask {
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 关联 management_task.id 一对一扩展（V12 列） */
    private Long managementTaskId;
    private Long comicId;
    private String sourceRef;
    private SourceType sourceType;
    private String sourcePath;
    private String batchId;
    private ImportTaskStatus status;
    private Integer progress;
    private Integer totalPages;
    private Integer downloadedPages;
    private String downloadMethod;
    private Long downloadSpeed;
    private Integer etaSeconds;
    private String errorMessage;
    private Integer retryCount;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Long durationMs;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
