package com.comicatlas.api.task.application.port.out;

import com.comicatlas.api.task.domain.model.ManagementTaskStatus;
import com.comicatlas.api.task.domain.model.TaskType;

import java.time.LocalDateTime;
import java.util.List;

/** 历史任务回填所需的旧任务读取与统一任务写入端口。 */
public interface LegacyTaskBackfillPersistencePort {
    List<LegacyTaskSnapshot> findUnboundImports();
    List<LegacyTaskSnapshot> findUnboundRecoveries();
    List<LegacyTaskSnapshot> findUnboundExports();
    List<LegacyTaskSnapshot> findUnboundScans();
    void bindImport(Long legacyTaskId, Long managementTaskId);
    void bindRecovery(Long legacyTaskId, Long managementTaskId);
    void bindExport(Long legacyTaskId, Long managementTaskId);
    void bindScan(Long legacyTaskId, Long managementTaskId);
    Long insertTask(TaskCreateCommand command);
    void insertItem(ItemCreateCommand command);

    record LegacyTaskSnapshot(Long id, Long comicId, String batchId, String status, Integer progress,
                              LocalDateTime startedAt, LocalDateTime completedAt) { }

    record TaskCreateCommand(TaskType taskType, String operation, String targetType, String batchId,
                             ManagementTaskStatus status, Integer progress, Integer totalCount,
                             Integer successCount, Integer failureCount, Integer cancelledCount,
                             Integer attempt, LocalDateTime startedAt, LocalDateTime completedAt) { }

    record ItemCreateCommand(Long taskId, String targetType, Long targetId, TaskType operationType,
                             ManagementTaskStatus status, Integer attempt, Integer progress, String lockKey,
                             LocalDateTime startedAt, LocalDateTime completedAt) { }
}
