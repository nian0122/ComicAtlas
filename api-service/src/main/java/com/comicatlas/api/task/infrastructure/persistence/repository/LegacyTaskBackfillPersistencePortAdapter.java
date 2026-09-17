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

    @Override public List<LegacyTaskSnapshot> findUnboundImports() {
        return legacyTaskMapper.selectUnboundImports().stream()
                .map(task -> new LegacyTaskSnapshot(task.getId(), task.getComicId(), task.getBatchId(),
                        task.getStatus() == null ? null : task.getStatus().name(), task.getProgress(),
                        task.getStartTime(), task.getEndTime())).toList();
    }
    @Override public List<LegacyTaskSnapshot> findUnboundRecoveries() {
        return legacyTaskMapper.selectUnboundRecoveries().stream()
                .map(task -> new LegacyTaskSnapshot(task.getId(), null, null,
                        task.getStatus() == null ? null : task.getStatus().name(), null,
                        task.getStartedAt(), task.getEndedAt())).toList();
    }
    @Override public List<LegacyTaskSnapshot> findUnboundExports() {
        return legacyTaskMapper.selectUnboundExports().stream()
                .map(task -> new LegacyTaskSnapshot(task.getId(), task.getComicId(), null,
                        task.getStatus() == null ? null : task.getStatus().name(), task.getProgress(),
                        null, task.getCompletedAt())).toList();
    }
    @Override public List<LegacyTaskSnapshot> findUnboundScans() {
        return legacyTaskMapper.selectUnboundScans().stream()
                .map(task -> new LegacyTaskSnapshot(task.getId(), null, null,
                        task.getStatus() == null ? null : task.getStatus().name(), null,
                        task.getStartedAt(), task.getEndedAt())).toList();
    }
    @Override public void bindImport(Long legacyTaskId, Long managementTaskId) {
        ImportTask task = new ImportTask(); task.setId(legacyTaskId); task.setManagementTaskId(managementTaskId);
        importTaskMapper.updateById(task);
    }
    @Override public void bindRecovery(Long legacyTaskId, Long managementTaskId) {
        RecoveryTask task = new RecoveryTask(); task.setId(legacyTaskId); task.setManagementTaskId(managementTaskId);
        recoveryTaskMapper.updateById(task);
    }
    @Override public void bindExport(Long legacyTaskId, Long managementTaskId) {
        ExportTask task = new ExportTask(); task.setId(legacyTaskId); task.setManagementTaskId(managementTaskId);
        exportTaskMapper.updateById(task);
    }
    @Override public void bindScan(Long legacyTaskId, Long managementTaskId) {
        DirectoryScanTask task = new DirectoryScanTask(); task.setId(legacyTaskId);
        task.setManagementTaskId(managementTaskId); directoryScanTaskMapper.updateById(task);
    }
    @Override public Long insertTask(TaskCreateCommand command) {
        ManagementTask task = new ManagementTask();
        task.setTaskType(command.taskType()); task.setOperation(command.operation());
        task.setTargetType(command.targetType()); task.setBatchId(command.batchId()); task.setBatch(false);
        task.setStatus(command.status()); task.setProgress(command.progress()); task.setTotalCount(command.totalCount());
        task.setSuccessCount(command.successCount()); task.setFailureCount(command.failureCount());
        task.setCancelledCount(command.cancelledCount()); task.setAttempt(command.attempt());
        task.setStartedAt(command.startedAt()); task.setCompletedAt(command.completedAt());
        managementTaskMapper.insert(task);
        return task.getId();
    }
    @Override public void insertItem(ItemCreateCommand command) {
        ManagementTaskItem item = new ManagementTaskItem();
        item.setTaskId(command.taskId()); item.setTargetType(command.targetType()); item.setTargetId(command.targetId());
        item.setOperationType(command.operationType()); item.setStatus(command.status()); item.setAttempt(command.attempt());
        item.setProgress(command.progress()); item.setLockKey(command.lockKey()); item.setStartedAt(command.startedAt());
        item.setCompletedAt(command.completedAt());
        managementTaskItemMapper.insert(item);
    }
}
