package com.comicatlas.api.task.application.port.out;

/** 批量任务应用服务查询幂等任务的输出端口。 */
public interface TaskIdempotencyQueryPort {
    TaskSnapshot findByIdempotencyKey(String idempotencyKey);

    record TaskSnapshot(Long id, String idempotencyPayloadHash) { }
}
