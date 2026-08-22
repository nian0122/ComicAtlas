package com.comicatlas.api.task.service;

import com.comicatlas.api.task.dto.ManagementTaskItemResponse;
import com.comicatlas.api.task.dto.ManagementTaskResponse;
import com.comicatlas.api.task.entity.ManagementTask;
import com.comicatlas.api.task.entity.ManagementTaskItem;
import org.springframework.stereotype.Component;

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
        response.setCreatedAt(task.getCreatedAt());
        response.setUpdatedAt(task.getUpdatedAt());
        response.setStartedAt(task.getStartedAt());
        response.setCompletedAt(task.getCompletedAt());
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
        response.setCreatedAt(item.getCreatedAt());
        response.setUpdatedAt(item.getUpdatedAt());
        response.setStartedAt(item.getStartedAt());
        response.setCompletedAt(item.getCompletedAt());
        return response;
    }
}
