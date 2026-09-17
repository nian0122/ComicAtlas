package com.comicatlas.api.task.application.service;

import com.comicatlas.api.task.domain.model.TaskType;

/** 任务模块内部持久化查询契约。 */
public interface TaskInternalQueryService {
    TaskSnapshot findByIdempotencyKey(String idempotencyKey);

    ItemSnapshot findActiveItem(String targetType, Long targetId, TaskType operationType);

    long countActiveItems(Long taskId);

    record TaskSnapshot(Long id, String idempotencyPayloadHash) { }

    record ItemSnapshot(Long id, Long taskId, String targetType, Long targetId) { }
}
