package com.comicatlas.api.importer.dto;

import com.comicatlas.common.dto.ScanResultDTO;
import lombok.Data;
import java.time.LocalDateTime;

@Data
public class DirectoryScanTaskVO {
    private Long id;
    private String status;
    private String directoryPath;
    private Integer totalItems;
    private ScanResultDTO result;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
}
