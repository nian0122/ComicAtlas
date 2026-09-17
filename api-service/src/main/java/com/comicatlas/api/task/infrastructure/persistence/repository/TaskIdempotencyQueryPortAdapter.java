package com.comicatlas.api.task.infrastructure.persistence.repository;

import com.comicatlas.api.task.application.port.out.TaskIdempotencyQueryPort;
import com.comicatlas.api.task.application.port.out.TaskQueryPersistencePort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 批量任务幂等查询端口的 MyBatis 适配器。 */
@Component
@RequiredArgsConstructor
public class TaskIdempotencyQueryPortAdapter implements TaskIdempotencyQueryPort {
    private final TaskQueryPersistencePort persistencePort;

    @Override
    public TaskSnapshot findByIdempotencyKey(String idempotencyKey) {
        TaskQueryPersistencePort.TaskSnapshot task = persistencePort.findByIdempotencyKey(idempotencyKey);
        return task == null ? null : new TaskSnapshot(task.id(), task.idempotencyPayloadHash(),
                task.taskType(), task.status() == null ? null : task.status().name(), task.totalCount());
    }
}
