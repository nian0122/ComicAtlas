package com.comicatlas.api.task.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.comicatlas.api.task.dto.CreateManagementTaskRequest;
import com.comicatlas.api.task.dto.ManagementTaskItemResponse;
import com.comicatlas.api.task.dto.ManagementTaskResponse;
import com.comicatlas.api.task.enums.ManagementTaskStatus;
import com.comicatlas.api.task.enums.TaskStage;
import com.comicatlas.api.task.enums.TaskType;
import com.comicatlas.api.task.persistence.entity.ManagementTask;
import com.comicatlas.api.task.persistence.entity.ManagementTaskItem;
import java.util.List;

/** 管理任务应用服务契约。 */
public interface ManagementTaskService {
    ManagementTaskResponse createTask(CreateManagementTaskRequest request, String idempotencyKey, String payload);
    IPage<ManagementTaskResponse> listTasks(int page, int size, TaskType type, ManagementTaskStatus status,
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
    ManagementTask findByIdempotencyKey(String idempotencyKey);
    ManagementTaskItem findActiveItem(String targetType, Long targetId, TaskType operationType);
    long countActiveItems(Long taskId);
    long countActiveMetadataItems(Long taskId, Long comicId);
    void reaggregateTask(Long taskId);
}
