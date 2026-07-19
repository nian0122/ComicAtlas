package com.comicatlas.api.importer.dto;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class ImportTaskVO {
    private Long id;
    private Long comicId;
    private String sourceRef;
    private String sourceType;
    private String sourcePath;
    private String batchId;
    private String status;
    private Integer progress;
    private Integer totalPages;
    private Integer downloadedPages;
    private String downloadMethod;
    private Long downloadSpeed;
    private Integer etaSeconds;
    private String errorMessage;
    private Integer retryCount;
    private Long durationMs;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private LocalDateTime createdAt;
}
