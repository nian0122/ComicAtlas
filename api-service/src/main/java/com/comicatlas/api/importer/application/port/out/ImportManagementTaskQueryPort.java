package com.comicatlas.api.importer.application.port.out;

import com.comicatlas.api.task.domain.model.TaskType;

/** 导入应用服务查询管理任务状态的输出端口。 */
public interface ImportManagementTaskQueryPort {
    TaskSnapshot findByIdempotencyKey(String idempotencyKey);

    ItemSnapshot findActiveItem(String targetType, Long targetId, TaskType operationType);

    record TaskSnapshot(Long id, String idempotencyPayloadHash) { }

    record ItemSnapshot(Long id) { }
}
