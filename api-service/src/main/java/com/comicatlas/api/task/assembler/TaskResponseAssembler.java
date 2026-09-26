package com.comicatlas.api.task.assembler;

import com.comicatlas.api.task.dto.ManagementTaskItemResponse;
import com.comicatlas.api.task.dto.ManagementTaskResponse;
import com.comicatlas.api.task.persistence.entity.ManagementTask;
import com.comicatlas.api.task.persistence.entity.ManagementTaskItem;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** 管理任务实体到接口响应模型的转换器。 */
@Component
public class TaskResponseAssembler {

    /** 转换任务主表响应。 */
    public ManagementTaskResponse toResponse(ManagementTask task) {
        ManagementTaskResponse response = new ManagementTaskResponse();
        response.setId(task.getId());
        response.setTaskType(task.getTaskType());
        response.setOperation(task.getOperation());
        response.setTargetType(task.getTargetType());
        response.setBatchId(task.getBatchId());
        response.setBatch(task.getBatch());
        response.setStatus(task.getStatus());
        response.setStage(task.getStage());
        response.setProgress(task.getProgress());
        response.setTotalCount(task.getTotalCount());
        response.setSuccessCount(task.getSuccessCount());
        response.setFailureCount(task.getFailureCount());
        response.setCancelledCount(task.getCancelledCount());
        response.setErrorMessage(task.getErrorMessage());
        response.setAttempt(task.getAttempt());
        response.setVersion(task.getVersion());
        response.setCreatedAt(toUtcInstant(task.getCreatedAt()));
        response.setUpdatedAt(toUtcInstant(task.getUpdatedAt()));
        response.setStartedAt(toUtcInstant(task.getStartedAt()));
        response.setCompletedAt(toUtcInstant(task.getCompletedAt()));
        return response;
    }

    /** 转换任务项响应。 */
    public ManagementTaskItemResponse toItemResponse(ManagementTaskItem item) {
        ManagementTaskItemResponse response = new ManagementTaskItemResponse();
        response.setId(item.getId());
        response.setTaskId(item.getTaskId());
        response.setTargetType(item.getTargetType());
        response.setTargetId(item.getTargetId());
        response.setOperationType(item.getOperationType());
        response.setStatus(item.getStatus());
        response.setAttempt(item.getAttempt());
        response.setProgress(item.getProgress());
        response.setResultRefType(item.getResultRefType());
        response.setResultRefId(item.getResultRefId());
        response.setErrorMessage(item.getErrorMessage());
        response.setVersion(item.getVersion());
        response.setCreatedAt(toUtcInstant(item.getCreatedAt()));
        response.setUpdatedAt(toUtcInstant(item.getUpdatedAt()));
        response.setStartedAt(toUtcInstant(item.getStartedAt()));
        response.setCompletedAt(toUtcInstant(item.getCompletedAt()));
        return response;
    }

    /** 任务 DATETIME 按 UTC 保存，接口统一输出带 Z 的绝对时间。 */
    private Instant toUtcInstant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
}
