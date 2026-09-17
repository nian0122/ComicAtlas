package com.comicatlas.api.importer.infrastructure.persistence.repository;

import com.comicatlas.api.importer.application.port.out.ImportTaskPersistencePort;
import com.comicatlas.api.importer.infrastructure.persistence.entity.ImportTask;
import com.comicatlas.api.importer.infrastructure.persistence.mapper.ImportTaskMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 导入重试持久化端口的 MyBatis 适配器。 */
@Component
@RequiredArgsConstructor
public class ImportTaskPersistencePortAdapter implements ImportTaskPersistencePort {
    private final ImportTaskMapper importTaskMapper;

    @Override
    public ImportTaskPersistencePort.ImportTaskSnapshot findByManagementTaskId(Long managementTaskId) {
        ImportTask importTask = importTaskMapper.selectByManagementTaskId(managementTaskId);
        if (importTask == null) {
            return null;
        }
        return new ImportTaskPersistencePort.ImportTaskSnapshot(importTask.getId(), importTask.getComicId(),
                importTask.getManagementTaskId(), importTask.getStatus(), importTask.getRetryCount(),
                importTask.getSourceType(), importTask.getSourcePath(), importTask.getSourceRef());
    }
}
