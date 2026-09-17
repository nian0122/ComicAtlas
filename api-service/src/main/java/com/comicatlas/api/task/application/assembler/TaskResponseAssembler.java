package com.comicatlas.api.task.application.assembler;

import com.comicatlas.api.task.interfaces.rest.dto.ManagementTaskItemResponse;
import com.comicatlas.api.task.interfaces.rest.dto.ManagementTaskResponse;
import com.comicatlas.api.task.application.port.out.TaskViewQueryPort.ItemSnapshot;
import com.comicatlas.api.task.application.port.out.TaskViewQueryPort.TaskSnapshot;
import com.comicatlas.api.task.application.port.out.TaskQueryPersistencePort;
import org.springframework.stereotype.Component;

/** 管理任务实体到接口响应模型的转换器。 */
@Component
public class TaskResponseAssembler {

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

    /** 转换任务写模型查询快照。 */
    public ManagementTaskResponse toResponse(TaskQueryPersistencePort.TaskSnapshot task) {
        return toResponse(new TaskSnapshot(task.id(), task.taskType(), task.operation(), task.targetType(),
                task.batchId(), task.batch(), task.status(), task.stage(), task.progress(), task.totalCount(),
                task.successCount(), task.failureCount(), task.cancelledCount(), task.errorMessage(), task.attempt(),
                task.version(), task.createdAt(), task.updatedAt(), task.startedAt(), task.completedAt()));
    }

    /** 转换任务项写模型查询快照。 */
    public ManagementTaskItemResponse toItemResponse(TaskQueryPersistencePort.ItemSnapshot item) {
        return toItemResponse(new ItemSnapshot(item.id(), item.taskId(), item.targetType(), item.targetId(),
                item.operationType(), item.status(), item.attempt(), item.progress(), item.resultRefType(),
                item.resultRefId(), item.errorMessage(), item.version(), item.createdAt(), item.updatedAt(),
                item.startedAt(), item.completedAt()));
    }
}
