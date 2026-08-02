package com.comicatlas.api.importer.dto;

import com.comicatlas.common.dto.ScanResultVO;
import lombok.Data;
import java.time.LocalDateTime;

@Data
public class DirectoryScanTaskVO {
    private Long id;
    private String status;
    private String directoryPath;
    private Integer totalItems;
    private ScanResultVO result;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
}
