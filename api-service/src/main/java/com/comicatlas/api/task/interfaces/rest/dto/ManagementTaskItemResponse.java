package com.comicatlas.api.task.interfaces.rest.dto;

import com.comicatlas.api.task.domain.model.ManagementTaskStatus;
import com.comicatlas.api.task.domain.model.TaskType;
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
