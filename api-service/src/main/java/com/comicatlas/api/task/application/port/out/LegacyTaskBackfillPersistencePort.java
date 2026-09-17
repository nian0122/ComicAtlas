package com.comicatlas.api.task.application.port.out;

import com.comicatlas.api.exporter.infrastructure.persistence.entity.ExportTask;
import com.comicatlas.api.importer.infrastructure.persistence.entity.DirectoryScanTask;
import com.comicatlas.api.importer.infrastructure.persistence.entity.ImportTask;
import com.comicatlas.api.recovery.infrastructure.persistence.entity.RecoveryTask;
import com.comicatlas.api.task.infrastructure.persistence.entity.ManagementTask;
import com.comicatlas.api.task.infrastructure.persistence.entity.ManagementTaskItem;

import java.util.List;

/** 历史任务回填所需的旧任务读取与统一任务写入端口。 */
public interface LegacyTaskBackfillPersistencePort {
    List<ImportTask> findUnboundImports();
    List<RecoveryTask> findUnboundRecoveries();
    List<ExportTask> findUnboundExports();
    List<DirectoryScanTask> findUnboundScans();
    void updateImport(ImportTask task);
    void updateRecovery(RecoveryTask task);
    void updateExport(ExportTask task);
    void updateScan(DirectoryScanTask task);
    void insertTask(ManagementTask task);
    void insertItem(ManagementTaskItem item);
}
