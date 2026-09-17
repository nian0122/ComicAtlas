package com.comicatlas.api.task.application.service.impl;

import com.comicatlas.api.task.application.port.out.LegacyTaskBackfillPersistencePort;
import com.comicatlas.api.task.application.port.out.LegacyTaskBackfillPersistencePort.ItemCreateCommand;
import com.comicatlas.api.task.application.port.out.LegacyTaskBackfillPersistencePort.LegacyTaskSnapshot;
import com.comicatlas.api.task.application.port.out.LegacyTaskBackfillPersistencePort.TaskCreateCommand;
import com.comicatlas.api.task.application.port.in.LegacyTaskBackfillService;
import com.comicatlas.api.task.domain.model.ManagementTaskStatus;
import com.comicatlas.api.task.domain.model.TaskType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 历史任务回填 — 为既有专表行（import/recovery/export/directory_scan）补齐 management_task 主表。
 * <p>
 * 幂等：只处理 {@code management_task_id IS NULL} 的行；重复执行不产生新任务。
 * 启动时由 {@code LegacyTaskBackfillRunner} 触发，测试可直接调用验证回填数量。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LegacyTaskBackfillServiceImpl implements LegacyTaskBackfillService {
    // 历史任务回填为内部运维应用服务，具体实现保持在任务业务包内。

    private final LegacyTaskBackfillPersistencePort persistencePort;

    /**
     * 回填全部四类历史任务，返回新建 management_task 数量。
     */
    @Transactional
    public int backfillAll() {
        int count = 0;
        count += backfillImports();
        count += backfillRecoveries();
        count += backfillExports();
        count += backfillScans();
        if (count > 0) {
            log.info("历史任务回填完成: 共 {} 条", count);
        }
        return count;
    }

    private int backfillImports() {
        List<LegacyTaskSnapshot> rows = persistencePort.findUnboundImports();
        int count = 0;
        for (LegacyTaskSnapshot task : rows) {
            String legacyStatus = task.status();
            boolean hasComicTarget = task.comicId() != null;
            String targetType = hasComicTarget ? "COMIC" : "IMPORT_TASK";
            Long targetId = hasComicTarget ? task.comicId() : task.id();
            TaskCreateCommand managementTask = baseTask(TaskType.IMPORT, "导入漫画", targetType,
                    task.batchId(), legacyStatus, task.progress(), task.startedAt(), task.completedAt());
            ItemCreateCommand item = baseItem(targetType, targetId, TaskType.IMPORT, managementTask);
            Long managementTaskId = insertPair(managementTask, item);
            persistencePort.bindImport(task.id(), managementTaskId);
            count++;
        }
        if (count > 0) {
            log.info("回填 import_task: {} 条", count);
        }
        return count;
    }

    private int backfillRecoveries() {
        List<LegacyTaskSnapshot> rows = persistencePort.findUnboundRecoveries();
        int count = 0;
        for (LegacyTaskSnapshot recoveryTask : rows) {
            String legacyStatus = recoveryTask.status();
            TaskCreateCommand managementTask = baseTask(TaskType.RECOVERY, "存储恢复", "SYSTEM",
                    null, legacyStatus, null, recoveryTask.startedAt(), recoveryTask.completedAt());
            ItemCreateCommand item = baseItem("SYSTEM", recoveryTask.id(), TaskType.RECOVERY, managementTask);
            Long managementTaskId = insertPair(managementTask, item);
            persistencePort.bindRecovery(recoveryTask.id(), managementTaskId);
            count++;
        }
        if (count > 0) {
            log.info("回填 recovery_task: {} 条", count);
        }
        return count;
    }

    private int backfillExports() {
        List<LegacyTaskSnapshot> rows = persistencePort.findUnboundExports();
        int count = 0;
        for (LegacyTaskSnapshot task : rows) {
            String legacyStatus = task.status();
            TaskCreateCommand managementTask = baseTask(TaskType.EXPORT, "导出漫画", "COMIC",
                    null, legacyStatus, task.progress(), null, task.completedAt());
            ItemCreateCommand item = baseItem("COMIC", task.comicId(), TaskType.EXPORT, managementTask);
            Long managementTaskId = insertPair(managementTask, item);
            persistencePort.bindExport(task.id(), managementTaskId);
            count++;
        }
        if (count > 0) {
            log.info("回填 export_task: {} 条", count);
        }
        return count;
    }

    private int backfillScans() {
        List<LegacyTaskSnapshot> rows = persistencePort.findUnboundScans();
        int count = 0;
        for (LegacyTaskSnapshot task : rows) {
            String legacyStatus = task.status();
            TaskCreateCommand managementTask = baseTask(TaskType.DIRECTORY_SCAN, "目录扫描", "SYSTEM",
                    null, legacyStatus, null, task.startedAt(), task.completedAt());
            ItemCreateCommand item = baseItem("SYSTEM", task.id(), TaskType.DIRECTORY_SCAN, managementTask);
            Long managementTaskId = insertPair(managementTask, item);
            persistencePort.bindScan(task.id(), managementTaskId);
            count++;
        }
        if (count > 0) {
            log.info("回填 directory_scan_task: {} 条", count);
        }
        return count;
    }

    private TaskCreateCommand baseTask(TaskType type, String operation, String targetType,
                                    String batchId, String legacyStatus, Integer progress,
                                    LocalDateTime startedAt, LocalDateTime completedAt) {
        ManagementTaskStatus st = mapStatus(legacyStatus);
        return new TaskCreateCommand(type, operation, targetType, batchId, st,
                progress != null ? progress : 0, 1,
                st == ManagementTaskStatus.SUCCEEDED ? 1 : 0,
                st == ManagementTaskStatus.FAILED ? 1 : 0,
                st == ManagementTaskStatus.CANCELLED ? 1 : 0,
                1, startedAt, completedAt);
    }

    private ItemCreateCommand baseItem(String targetType, Long targetId, TaskType operation,
                                        TaskCreateCommand managementTask) {
        String lockKey = managementTask.status().isProcessing()
                ? targetType + ":" + targetId + ":" + operation.name() : null;
        return new ItemCreateCommand(null, targetType, targetId, operation, managementTask.status(),
                1, managementTask.progress(), lockKey, managementTask.startedAt(), managementTask.completedAt());
    }

    private Long insertPair(TaskCreateCommand managementTask, ItemCreateCommand item) {
        Long managementTaskId = persistencePort.insertTask(managementTask);
        persistencePort.insertItem(new ItemCreateCommand(managementTaskId, item.targetType(), item.targetId(),
                item.operationType(), item.status(), item.attempt(), item.progress(), item.lockKey(),
                item.startedAt(), item.completedAt()));
        return managementTaskId;
    }

    /**
     * 旧状态 → ManagementTaskStatus 映射；未识别状态按 QUEUED 处理。
     */
    private ManagementTaskStatus mapStatus(String legacyStatus) {
        if (legacyStatus == null) {
            return ManagementTaskStatus.QUEUED;
        }
        return switch (legacyStatus) {
            case "SUCCESS", "SUCCEEDED" -> ManagementTaskStatus.SUCCEEDED;
            case "FAILED" -> ManagementTaskStatus.FAILED;
            case "CANCELLED" -> ManagementTaskStatus.CANCELLED;
            case "RUNNING", "IMPORTING" -> ManagementTaskStatus.RUNNING;
            default -> ManagementTaskStatus.QUEUED;
        };
    }
}
