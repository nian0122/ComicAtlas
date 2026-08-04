package com.comicatlas.api.management.dto;

import com.comicatlas.common.enums.ManagementTaskStatus;
import com.comicatlas.common.enums.TaskType;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 管理任务响应。
 */
@Data
public class ManagementTaskResponse {

    private Long id;
    private TaskType taskType;
    private String operation;
    private String targetType;
    private String batchId;
    private Boolean isBatch;
    private ManagementTaskStatus status;
    private String stage;
    private Integer progress;
    private Integer totalCount;
    private Integer successCount;
    private Integer failureCount;
    private Integer cancelledCount;
    private String errorMessage;
    private Integer attempt;
    private Integer version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
}
