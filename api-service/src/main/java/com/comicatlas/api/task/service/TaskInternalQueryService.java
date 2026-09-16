package com.comicatlas.api.task.service;

import com.comicatlas.api.task.enums.TaskType;
import com.comicatlas.api.task.persistence.entity.ManagementTask;
import com.comicatlas.api.task.persistence.entity.ManagementTaskItem;

/** 任务模块内部持久化查询契约。 */
public interface TaskInternalQueryService {
    ManagementTask findByIdempotencyKey(String idempotencyKey);

    ManagementTaskItem findActiveItem(String targetType, Long targetId, TaskType operationType);

    long countActiveItems(Long taskId);
}
