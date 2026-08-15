package com.comicatlas.api.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.comicatlas.api.export.entity.ExportTask;
import com.comicatlas.api.export.mapper.ExportTaskMapper;
import com.comicatlas.api.importer.entity.DirectoryScanTask;
import com.comicatlas.api.importer.entity.ImportTask;
import com.comicatlas.api.importer.entity.RecoveryTask;
import com.comicatlas.api.importer.mapper.DirectoryScanTaskMapper;
import com.comicatlas.api.importer.mapper.ImportTaskMapper;
import com.comicatlas.api.importer.mapper.RecoveryTaskMapper;
import com.comicatlas.api.management.entity.ManagementTask;
import com.comicatlas.api.management.entity.ManagementTaskItem;
import com.comicatlas.api.management.mapper.ManagementTaskItemMapper;
import com.comicatlas.api.management.mapper.ManagementTaskMapper;
import com.comicatlas.api.common.enums.ManagementTaskStatus;
import com.comicatlas.api.common.enums.TaskType;
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
public class LegacyTaskBackfillService {

    private final ImportTaskMapper importTaskMapper;
    private final RecoveryTaskMapper recoveryTaskMapper;
    private final ExportTaskMapper exportTaskMapper;
    private final DirectoryScanTaskMapper directoryScanTaskMapper;
    private final ManagementTaskMapper managementTaskMapper;
    private final ManagementTaskItemMapper managementTaskItemMapper;

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
        List<ImportTask> rows = importTaskMapper.selectList(
                new LambdaQueryWrapper<ImportTask>().isNull(ImportTask::getManagementTaskId));
        int count = 0;
        for (ImportTask task : rows) {
            String legacyStatus = task.getStatus() == null ? null : task.getStatus().name();
            boolean hasComicTarget = task.getComicId() != null;
            String targetType = hasComicTarget ? "COMIC" : "IMPORT_TASK";
            Long targetId = hasComicTarget ? task.getComicId() : task.getId();
            ManagementTask managementTask = baseTask(TaskType.IMPORT, "导入漫画", targetType,
                    task.getBatchId(), legacyStatus, task.getProgress(),
                    task.getStartTime(), task.getEndTime());
            ManagementTaskItem item = baseItem(
                    managementTask, targetType, targetId, TaskType.IMPORT, legacyStatus);
            insertPair(managementTask, item);
            task.setManagementTaskId(managementTask.getId());
            importTaskMapper.updateById(task);
            count++;
        }
        if (count > 0) {
            log.info("回填 import_task: {} 条", count);
        }
        return count;
    }

    private int backfillRecoveries() {
        List<RecoveryTask> rows = recoveryTaskMapper.selectList(
                new LambdaQueryWrapper<RecoveryTask>().isNull(RecoveryTask::getManagementTaskId));
        int count = 0;
        for (RecoveryTask recoveryTask : rows) {
            String legacyStatus = recoveryTask.getStatus() == null ? null : recoveryTask.getStatus().name();
            ManagementTask managementTask = baseTask(TaskType.RECOVERY, "存储恢复", "SYSTEM",
                    null, legacyStatus, null, recoveryTask.getStartedAt(), recoveryTask.getEndedAt());
            ManagementTaskItem item = baseItem(managementTask, "SYSTEM", recoveryTask.getId(), TaskType.RECOVERY, legacyStatus);
            insertPair(managementTask, item);
            recoveryTask.setManagementTaskId(managementTask.getId());
            recoveryTaskMapper.updateById(recoveryTask);
            count++;
        }
        if (count > 0) {
            log.info("回填 recovery_task: {} 条", count);
        }
        return count;
    }

    private int backfillExports() {
        List<ExportTask> rows = exportTaskMapper.selectList(
                new LambdaQueryWrapper<ExportTask>().isNull(ExportTask::getManagementTaskId));
        int count = 0;
        for (ExportTask task : rows) {
            String legacyStatus = task.getStatus() == null ? null : task.getStatus().name();
            ManagementTask managementTask = baseTask(TaskType.EXPORT, "导出漫画", "COMIC",
                    null, legacyStatus, task.getProgress(), null, task.getCompletedAt());
            ManagementTaskItem item = baseItem(managementTask, "COMIC", task.getComicId(), TaskType.EXPORT, legacyStatus);
            insertPair(managementTask, item);
            task.setManagementTaskId(managementTask.getId());
            exportTaskMapper.updateById(task);
            count++;
        }
        if (count > 0) {
            log.info("回填 export_task: {} 条", count);
        }
        return count;
    }

    private int backfillScans() {
        List<DirectoryScanTask> rows = directoryScanTaskMapper.selectList(
                new LambdaQueryWrapper<DirectoryScanTask>().isNull(DirectoryScanTask::getManagementTaskId));
        int count = 0;
        for (DirectoryScanTask task : rows) {
            String legacyStatus = task.getStatus() == null ? null : task.getStatus().name();
            ManagementTask managementTask = baseTask(TaskType.DIRECTORY_SCAN, "目录扫描", "SYSTEM",
                    null, legacyStatus, null, task.getStartedAt(), task.getEndedAt());
            ManagementTaskItem item = baseItem(managementTask, "SYSTEM", task.getId(), TaskType.DIRECTORY_SCAN, legacyStatus);
            insertPair(managementTask, item);
            task.setManagementTaskId(managementTask.getId());
            directoryScanTaskMapper.updateById(task);
            count++;
        }
        if (count > 0) {
            log.info("回填 directory_scan_task: {} 条", count);
        }
        return count;
    }

    private ManagementTask baseTask(TaskType type, String operation, String targetType,
                                    String batchId, String legacyStatus, Integer progress,
                                    LocalDateTime startedAt, LocalDateTime completedAt) {
        ManagementTask managementTask = new ManagementTask();
        managementTask.setTaskType(type);
        managementTask.setOperation(operation);
        managementTask.setTargetType(targetType);
        managementTask.setBatchId(batchId);
        managementTask.setBatch(false);
        ManagementTaskStatus st = mapStatus(legacyStatus);
        managementTask.setStatus(st);
        managementTask.setProgress(progress != null ? progress : 0);
        managementTask.setTotalCount(1);
        managementTask.setSuccessCount(st == ManagementTaskStatus.SUCCEEDED ? 1 : 0);
        managementTask.setFailureCount(st == ManagementTaskStatus.FAILED ? 1 : 0);
        managementTask.setCancelledCount(st == ManagementTaskStatus.CANCELLED ? 1 : 0);
        managementTask.setAttempt(1);
        managementTask.setStartedAt(startedAt);
        managementTask.setCompletedAt(completedAt);
        return managementTask;
    }

    private ManagementTaskItem baseItem(ManagementTask managementTask, String targetType, Long targetId,
                                        TaskType operation, String legacyStatus) {
        ManagementTaskItem item = new ManagementTaskItem();
        item.setTaskId(managementTask.getId());
        item.setTargetType(targetType);
        item.setTargetId(targetId);
        item.setOperationType(operation);
        ManagementTaskStatus st = managementTask.getStatus();
        item.setStatus(st);
        item.setAttempt(1);
        item.setProgress(managementTask.getProgress());
        if (st.isProcessing()) {
            item.setLockKey(ManagementTaskItem.buildLockKey(targetType, targetId, operation));
        }
        item.setStartedAt(managementTask.getStartedAt());
        item.setCompletedAt(managementTask.getCompletedAt());
        return item;
    }

    private void insertPair(ManagementTask managementTask, ManagementTaskItem item) {
        managementTaskMapper.insert(managementTask);
        item.setTaskId(managementTask.getId());
        managementTaskItemMapper.insert(item);
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
