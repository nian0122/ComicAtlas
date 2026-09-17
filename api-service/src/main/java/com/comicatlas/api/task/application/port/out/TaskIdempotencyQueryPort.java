package com.comicatlas.api.task.application.port.out;

import com.comicatlas.api.task.domain.model.TaskType;

/** 批量任务应用服务查询幂等任务的输出端口。 */
public interface TaskIdempotencyQueryPort {
    TaskSnapshot findByIdempotencyKey(String idempotencyKey);

    record TaskSnapshot(Long id, String idempotencyPayloadHash, TaskType taskType,
                        String status, Integer totalCount) { }
}
