package com.comicatlas.api.management.dto;

import com.comicatlas.api.common.enums.ManagementTaskStatus;
import com.comicatlas.api.common.enums.TaskType;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 管理任务目标项响应。
 */
@Data
public class ManagementTaskItemResponse {

    private Long id;
    private Long taskId;
    private String targetType;
    private Long targetId;
    private TaskType operationType;
    private ManagementTaskStatus status;
    private Integer attempt;
    private Integer progress;
    private String resultRefType;
    private Long resultRefId;
    private String errorMessage;
    private Integer version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
}
