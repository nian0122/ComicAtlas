package com.comicatlas.api.trash.application.port.out;

import com.comicatlas.api.trash.infrastructure.persistence.entity.TrashManifestRecord;

/** 回收清单应用服务访问清单持久化的输出端口。 */
public interface TrashManifestPersistencePort {

    TrashManifestRecord findByTaskId(Long taskId);

    TrashManifestRecord findLatest(String targetType, Long targetId);

    void insert(TrashManifestRecord record);
}
