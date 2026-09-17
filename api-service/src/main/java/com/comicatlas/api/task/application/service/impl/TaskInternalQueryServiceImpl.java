package com.comicatlas.api.task.application.service.impl;

import com.comicatlas.api.task.infrastructure.persistence.entity.ManagementTask;
import com.comicatlas.api.task.infrastructure.persistence.entity.ManagementTaskItem;
import com.comicatlas.api.task.domain.model.TaskType;
import com.comicatlas.api.task.application.service.TaskInternalQueryService;
import com.comicatlas.api.task.application.port.out.TaskQueryPersistencePort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 任务模块内部持久化查询，不负责接口响应组装。 */
@Service
@RequiredArgsConstructor
public class TaskInternalQueryServiceImpl implements TaskInternalQueryService {
    // 任务内部查询契约由应用服务公开，具体实现保持在任务业务包内。

    private final TaskQueryPersistencePort persistencePort;

    /** 按幂等键查询任务，空键返回 null。 */
    public ManagementTask findByIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return null;
        }
        return persistencePort.findByIdempotencyKey(idempotencyKey);
    }

    /** 查询目标当前活跃任务项。 */
    public ManagementTaskItem findActiveItem(String targetType, Long targetId, TaskType operationType) {
        return persistencePort.findActiveItem(targetType, targetId, operationType);
    }

    /** 统计任务下尚未结束的任务项数量。 */
    public long countActiveItems(Long taskId) {
        return persistencePort.countActiveItems(taskId);
    }
}
