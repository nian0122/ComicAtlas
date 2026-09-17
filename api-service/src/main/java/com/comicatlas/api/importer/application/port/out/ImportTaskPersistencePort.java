package com.comicatlas.api.importer.application.port.out;

import com.comicatlas.api.importer.infrastructure.persistence.entity.ImportTask;

/** 导入重试所需的持久化端口。 */
public interface ImportTaskPersistencePort {
    ImportTask findByManagementTaskId(Long managementTaskId);
}
