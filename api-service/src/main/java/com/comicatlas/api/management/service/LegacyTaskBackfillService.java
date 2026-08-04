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
import com.comicatlas.common.enums.ManagementTaskStatus;
import com.comicatlas.common.enums.TaskType;
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
        for (ImportTask t : rows) {
            ManagementTask mt = baseTask(TaskType.IMPORT, "导入漫画", "COMIC",
                    t.getBatchId(), t.getStatus(), t.getProgress(),
                    t.getStartTime(), t.getEndTime());
            ManagementTaskItem item = baseItem(mt, "COMIC", t.getComicId(), TaskType.IMPORT, t.getStatus());
            insertPair(mt, item);
            t.setManagementTaskId(mt.getId());
            importTaskMapper.updateById(t);
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
        for (RecoveryTask t : rows) {
            ManagementTask mt = baseTask(TaskType.RECOVERY, "存储恢复", "SYSTEM",
                    null, t.getStatus(), null, t.getStartedAt(), t.getEndedAt());
            ManagementTaskItem item = baseItem(mt, "SYSTEM", t.getId(), TaskType.RECOVERY, t.getStatus());
            insertPair(mt, item);
            t.setManagementTaskId(mt.getId());
            recoveryTaskMapper.updateById(t);
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
        for (ExportTask t : rows) {
            ManagementTask mt = baseTask(TaskType.EXPORT, "导出漫画", "COMIC",
                    null, t.getStatus(), t.getProgress(), null, t.getCompletedAt());
            ManagementTaskItem item = baseItem(mt, "COMIC", t.getComicId(), TaskType.EXPORT, t.getStatus());
            insertPair(mt, item);
            t.setManagementTaskId(mt.getId());
            exportTaskMapper.updateById(t);
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
        for (DirectoryScanTask t : rows) {
            ManagementTask mt = baseTask(TaskType.DIRECTORY_SCAN, "目录扫描", "SYSTEM",
                    null, t.getStatus(), null, t.getStartedAt(), t.getEndedAt());
            ManagementTaskItem item = baseItem(mt, "SYSTEM", t.getId(), TaskType.DIRECTORY_SCAN, t.getStatus());
            insertPair(mt, item);
            t.setManagementTaskId(mt.getId());
            directoryScanTaskMapper.updateById(t);
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
        ManagementTask mt = new ManagementTask();
        mt.setTaskType(type);
        mt.setOperation(operation);
        mt.setTargetType(targetType);
        mt.setBatchId(batchId);
        mt.setIsBatch(false);
        ManagementTaskStatus st = mapStatus(legacyStatus);
        mt.setStatus(st);
        mt.setProgress(progress != null ? progress : 0);
        mt.setTotalCount(1);
        mt.setSuccessCount(st == ManagementTaskStatus.SUCCEEDED ? 1 : 0);
        mt.setFailureCount(st == ManagementTaskStatus.FAILED ? 1 : 0);
        mt.setCancelledCount(st == ManagementTaskStatus.CANCELLED ? 1 : 0);
        mt.setAttempt(1);
        mt.setStartedAt(startedAt);
        mt.setCompletedAt(completedAt);
        return mt;
    }

    private ManagementTaskItem baseItem(ManagementTask mt, String targetType, Long targetId,
                                        TaskType op, String legacyStatus) {
        ManagementTaskItem item = new ManagementTaskItem();
        item.setTaskId(mt.getId());
        item.setTargetType(targetType);
        item.setTargetId(targetId);
        item.setOperationType(op);
        ManagementTaskStatus st = mt.getStatus();
        item.setStatus(st);
        item.setAttempt(1);
        item.setProgress(mt.getProgress());
        if (st.isProcessing()) {
            item.setLockKey(ManagementTaskItem.buildLockKey(targetType, targetId, op));
        }
        item.setStartedAt(mt.getStartedAt());
        item.setCompletedAt(mt.getCompletedAt());
        return item;
    }

    private void insertPair(ManagementTask mt, ManagementTaskItem item) {
        managementTaskMapper.insert(mt);
        item.setTaskId(mt.getId());
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
