package com.comicatlas.api.operation.dto;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class OperationLogVO {
    private Long id;
    private String traceId;
    private String module;
    private String action;
    private String businessId;
    private String detail;
    private LocalDateTime createdAt;
}
