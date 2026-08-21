package com.comicatlas.api.export.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ExportTaskVO {
    private Long id;
    private Long comicId;
    private String format;
    private String status;
    private Integer progress;
    private String outputRoot;
    private String outputPath;
    private Long outputSize;
    private String errorMsg;
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;

    /** 计算的物理路径: exportDir + "/" + outputPath */
    private String physicalPath;
}
