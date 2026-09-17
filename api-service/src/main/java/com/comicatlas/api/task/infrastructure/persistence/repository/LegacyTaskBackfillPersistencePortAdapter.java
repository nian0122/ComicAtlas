package com.comicatlas.api.task.infrastructure.persistence.repository;

import com.comicatlas.api.exporter.infrastructure.persistence.entity.ExportTask;
import com.comicatlas.api.exporter.infrastructure.persistence.mapper.ExportTaskMapper;
import com.comicatlas.api.importer.infrastructure.persistence.entity.DirectoryScanTask;
import com.comicatlas.api.importer.infrastructure.persistence.entity.ImportTask;
import com.comicatlas.api.importer.infrastructure.persistence.mapper.DirectoryScanTaskMapper;
import com.comicatlas.api.importer.infrastructure.persistence.mapper.ImportTaskMapper;
import com.comicatlas.api.recovery.infrastructure.persistence.entity.RecoveryTask;
import com.comicatlas.api.recovery.infrastructure.persistence.mapper.RecoveryTaskMapper;
import com.comicatlas.api.task.application.port.out.LegacyTaskBackfillPersistencePort;
import com.comicatlas.api.task.infrastructure.persistence.entity.ManagementTask;
import com.comicatlas.api.task.infrastructure.persistence.entity.ManagementTaskItem;
import com.comicatlas.api.task.infrastructure.persistence.mapper.LegacyTaskMapper;
import com.comicatlas.api.task.infrastructure.persistence.mapper.ManagementTaskItemMapper;
import com.comicatlas.api.task.infrastructure.persistence.mapper.ManagementTaskMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/** 历史任务回填端口的 MyBatis 适配器。 */
@Component
@RequiredArgsConstructor
public class LegacyTaskBackfillPersistencePortAdapter implements LegacyTaskBackfillPersistencePort {
    private final ImportTaskMapper importTaskMapper;
    private final RecoveryTaskMapper recoveryTaskMapper;
    private final ExportTaskMapper exportTaskMapper;
    private final DirectoryScanTaskMapper directoryScanTaskMapper;
    private final ManagementTaskMapper managementTaskMapper;
    private final ManagementTaskItemMapper managementTaskItemMapper;
    private final LegacyTaskMapper legacyTaskMapper;

    @Override public List<ImportTask> findUnboundImports() { return legacyTaskMapper.selectUnboundImports(); }
    @Override public List<RecoveryTask> findUnboundRecoveries() { return legacyTaskMapper.selectUnboundRecoveries(); }
    @Override public List<ExportTask> findUnboundExports() { return legacyTaskMapper.selectUnboundExports(); }
    @Override public List<DirectoryScanTask> findUnboundScans() { return legacyTaskMapper.selectUnboundScans(); }
    @Override public void updateImport(ImportTask task) { importTaskMapper.updateById(task); }
    @Override public void updateRecovery(RecoveryTask task) { recoveryTaskMapper.updateById(task); }
    @Override public void updateExport(ExportTask task) { exportTaskMapper.updateById(task); }
    @Override public void updateScan(DirectoryScanTask task) { directoryScanTaskMapper.updateById(task); }
    @Override public void insertTask(ManagementTask task) { managementTaskMapper.insert(task); }
    @Override public void insertItem(ManagementTaskItem item) { managementTaskItemMapper.insert(item); }
}
