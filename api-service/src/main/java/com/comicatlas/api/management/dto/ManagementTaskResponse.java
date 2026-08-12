package com.comicatlas.api.management.dto;

import com.comicatlas.contract.common.enums.ManagementTaskStatus;
import com.comicatlas.contract.common.enums.TaskType;
import com.fasterxml.jackson.annotation.JsonProperty;
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
    private Long targetId;
    private String targetName;
    private String batchId;
    /** 是否批量任务（REST 键 isBatch，内部名 batch） */
    @JsonProperty("isBatch")
    private Boolean batch;
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
