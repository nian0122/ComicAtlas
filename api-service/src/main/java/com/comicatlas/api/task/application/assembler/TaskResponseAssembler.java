package com.comicatlas.api.task.application.assembler;

import com.comicatlas.api.task.interfaces.rest.dto.ManagementTaskItemResponse;
import com.comicatlas.api.task.interfaces.rest.dto.ManagementTaskResponse;
import com.comicatlas.api.task.application.port.out.TaskViewQueryPort.ItemSnapshot;
import com.comicatlas.api.task.application.port.out.TaskViewQueryPort.TaskSnapshot;
import com.comicatlas.api.task.infrastructure.persistence.entity.ManagementTask;
import com.comicatlas.api.task.infrastructure.persistence.entity.ManagementTaskItem;
import org.springframework.stereotype.Component;

/** 管理任务实体到接口响应模型的转换器。 */
@Component
public class TaskResponseAssembler {

    /** 兼容写侧应用服务：将基础设施实体立即转换为查询快照。 */
    public ManagementTaskResponse toResponse(ManagementTask task) {
        return toResponse(new TaskSnapshot(task.getId(), task.getTaskType(), task.getOperation(),
                task.getTargetType(), task.getBatchId(), task.getBatch(), task.getStatus(), task.getStage(),
                task.getProgress(), task.getTotalCount(), task.getSuccessCount(), task.getFailureCount(),
                task.getCancelledCount(), task.getErrorMessage(), task.getAttempt(), task.getVersion(),
                task.getCreatedAt(), task.getUpdatedAt(), task.getStartedAt(), task.getCompletedAt()));
    }

    /** 兼容写侧应用服务：将基础设施实体立即转换为查询快照。 */
    public ManagementTaskItemResponse toItemResponse(ManagementTaskItem item) {
        return toItemResponse(new ItemSnapshot(item.getId(), item.getTaskId(), item.getTargetType(), item.getTargetId(),
                item.getOperationType(), item.getStatus(), item.getAttempt(), item.getProgress(),
                item.getResultRefType(), item.getResultRefId(), item.getErrorMessage(), item.getVersion(),
                item.getCreatedAt(), item.getUpdatedAt(), item.getStartedAt(), item.getCompletedAt()));
    }

    /** 转换任务主表响应。 */
    public ManagementTaskResponse toResponse(TaskSnapshot task) {
        ManagementTaskResponse response = new ManagementTaskResponse();
        response.setId(task.id()); response.setTaskType(task.taskType()); response.setOperation(task.operation());
        response.setTargetType(task.targetType()); response.setBatchId(task.batchId()); response.setBatch(task.batch());
        response.setStatus(task.status()); response.setStage(task.stage()); response.setProgress(task.progress());
        response.setTotalCount(task.totalCount()); response.setSuccessCount(task.successCount());
        response.setFailureCount(task.failureCount()); response.setCancelledCount(task.cancelledCount());
        response.setErrorMessage(task.errorMessage()); response.setAttempt(task.attempt());
        response.setVersion(task.version()); response.setCreatedAt(task.createdAt()); response.setUpdatedAt(task.updatedAt());
        response.setStartedAt(task.startedAt()); response.setCompletedAt(task.completedAt());
        return response;
    }

    /** 转换任务项响应。 */
    public ManagementTaskItemResponse toItemResponse(ItemSnapshot item) {
        ManagementTaskItemResponse response = new ManagementTaskItemResponse();
        response.setId(item.id()); response.setTaskId(item.taskId()); response.setTargetType(item.targetType());
        response.setTargetId(item.targetId()); response.setOperationType(item.operationType());
        response.setStatus(item.status()); response.setAttempt(item.attempt()); response.setProgress(item.progress());
        response.setResultRefType(item.resultRefType()); response.setResultRefId(item.resultRefId());
        response.setErrorMessage(item.errorMessage()); response.setVersion(item.version());
        response.setCreatedAt(item.createdAt()); response.setUpdatedAt(item.updatedAt());
        response.setStartedAt(item.startedAt()); response.setCompletedAt(item.completedAt());
        return response;
    }
}
