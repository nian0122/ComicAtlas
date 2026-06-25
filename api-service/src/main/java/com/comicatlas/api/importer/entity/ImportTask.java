package com.comicatlas.api.importer.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("import_task")
public class ImportTask {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long comicId;
    private String sourceUrl;
    private String status;
    private Integer progress;
    private Integer totalPages;
    private Integer downloadedPages;
    private Integer currentPage;
    private Long downloadedBytes;
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
