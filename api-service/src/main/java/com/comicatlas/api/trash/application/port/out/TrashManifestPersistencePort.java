package com.comicatlas.api.trash.application.port.out;

/** 回收清单应用服务访问清单持久化的输出端口。 */
public interface TrashManifestPersistencePort {

    Snapshot findByTaskId(Long taskId);

    Snapshot findLatest(String targetType, Long targetId);

    void insert(CreateCommand command);

    record Snapshot(Long taskId, String targetType, Long targetId, String manifestJson) {
    }

    record CreateCommand(Long taskId, String targetType, Long targetId, String manifestJson) {
    }
}
