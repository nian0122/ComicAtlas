package com.comicatlas.api.importer.application.port.out;

import com.comicatlas.api.importer.domain.model.ImportTaskStatus;
import com.comicatlas.contract.common.enums.SourceType;

/** 导入重试所需的持久化端口。 */
public interface ImportTaskPersistencePort {
    ImportTaskSnapshot findByManagementTaskId(Long managementTaskId);

    record ImportTaskSnapshot(Long id, Long comicId, Long managementTaskId, ImportTaskStatus status,
                              Integer retryCount, SourceType sourceType, String sourcePath, String sourceRef) {
    }
}
