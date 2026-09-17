package com.comicatlas.api.task.application.port.in;

import com.comicatlas.api.shared.application.model.PageResult;
import com.comicatlas.api.task.interfaces.rest.dto.CreateManagementTaskRequest;
import com.comicatlas.api.task.interfaces.rest.dto.ManagementTaskItemResponse;
import com.comicatlas.api.task.interfaces.rest.dto.ManagementTaskResponse;
import com.comicatlas.api.task.domain.model.ManagementTaskStatus;
import com.comicatlas.api.task.domain.model.TaskStage;
import com.comicatlas.api.task.domain.model.TaskType;
import com.comicatlas.api.task.application.service.TaskInternalQueryService;
import java.util.List;

/** 管理任务应用服务契约。 */
public interface ManagementTaskService {
    ManagementTaskResponse createTask(CreateManagementTaskRequest request, String idempotencyKey, String payload);
    PageResult<ManagementTaskResponse> listTasks(int page, int size, TaskType type, ManagementTaskStatus status,
                                            String batchId, String targetType, Long targetId);
    ManagementTaskResponse getTask(Long taskId);
    List<ManagementTaskItemResponse> getTaskItems(Long taskId);
    ManagementTaskResponse cancelTask(Long taskId);
    ManagementTaskResponse retryTask(Long taskId);
    void resetTaskState(Long taskId);
    boolean updateStage(Long managementTaskId, TaskStage stage, Integer progress);
    ManagementTaskItemResponse updateItemStatus(Long itemId, ManagementTaskStatus newStatus,
                                                 String errorMessage, String resultRefType, Long resultRefId);
    ManagementTaskItemResponse updateItemStatus(Long itemId, ManagementTaskStatus newStatus,
                                                 String errorMessage, String resultRefType, Long resultRefId,
                                                 int attempt);
    boolean updateItemProgress(Long itemId, int attempt, int progress, String stage);
    TaskInternalQueryService.TaskSnapshot findByIdempotencyKey(String idempotencyKey);
    TaskInternalQueryService.ItemSnapshot findActiveItem(String targetType, Long targetId, TaskType operationType);
    long countActiveItems(Long taskId);
    long countActiveMetadataItems(Long taskId, Long comicId);
    void reaggregateTask(Long taskId);
}
