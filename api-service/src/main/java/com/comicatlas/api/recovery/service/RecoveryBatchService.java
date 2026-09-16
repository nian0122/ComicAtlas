package com.comicatlas.api.recovery.service;

import com.comicatlas.api.recovery.dto.RecoveryProgressVO;
import com.comicatlas.api.recovery.engine.RecoveryEngine;
import com.comicatlas.api.recovery.enums.RecoveryTaskStatus;
import com.comicatlas.api.recovery.persistence.entity.RecoveryTask;
import com.comicatlas.api.recovery.persistence.mapper.RecoveryTaskMapper;
import com.comicatlas.api.task.dto.ManagementTaskItemResponse;
import com.comicatlas.api.task.enums.ManagementTaskStatus;
import com.comicatlas.api.task.enums.TaskType;
import com.comicatlas.api.task.service.ManagementTaskService;
import com.comicatlas.common.event.RecoveryFailedEvent;
import com.comicatlas.common.event.RecoveryScanCompletedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.EnumSet;

/** 恢复批次业务编排：执行逐本恢复、累计进度并维护恢复任务生命周期。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecoveryBatchService {
    private static final String EVENT_KEY_PREFIX = "mq:event:";
    private static final String TARGET_TYPE_SYSTEM = "SYSTEM";
    private static final String RESULT_REF_TYPE = "RECOVERY_TASK";
    private static final String STAGE_RECOVERY = "RECOVERY";
    private static final Duration IDEMPOTENCY_TTL = Duration.ofDays(1);
    private static final EnumSet<RecoveryTaskStatus> TERMINAL_STATUSES = EnumSet.of(
            RecoveryTaskStatus.SUCCEEDED, RecoveryTaskStatus.FAILED, RecoveryTaskStatus.CANCELLED);

    private final RecoveryEngine recoveryEngine;
    private final RecoveryTaskMapper recoveryTaskMapper;
    private final RedisTemplate<String, Object> redisTemplate;
    private final ManagementTaskService managementTaskService;

    public void processScanCompleted(RecoveryScanCompletedEvent event) {
        String key = EVENT_KEY_PREFIX + event.eventId();
        if (isProcessed(key)) {
            return;
        }
        RecoveryTask task = recoveryTaskMapper.selectById(event.taskId());
        if (task == null || TERMINAL_STATUSES.contains(task.getStatus())) {
            markProcessed(key);
            return;
        }
        task.setStatus(RecoveryTaskStatus.RUNNING);
        task.setStartedAt(LocalDateTime.now());
        task.setTotalComics(event.comicIds().size());
        task.setRecoveredComics(0); task.setSkippedComics(0);
        task.setPlaceholderComics(0); task.setErrorComics(0);
        task.setErrorMessage(null); task.setErrorDetails(null);
        recoveryTaskMapper.updateById(task);
        ManagementTaskItemResponse item = syncItem(event.taskId(), ManagementTaskStatus.RUNNING, null, null, null);
        int processed = 0, recovered = 0, skipped = 0, placeholder = 0, errors = 0;
        for (Long comicId : event.comicIds()) {
            try {
                RecoveryProgressVO progress = recoveryEngine.processComicDir(comicId, processed);
                processed = progress.totalComics(); recovered += progress.recoveredComics();
                skipped += progress.skippedComics(); placeholder += progress.placeholderComics();
                errors += progress.errorComics();
                task.setRecoveredComics(recovered); task.setSkippedComics(skipped);
                task.setPlaceholderComics(placeholder); task.setErrorComics(errors);
                if (progress.lastError() != null) {
                    task.setErrorMessage(progress.lastError());
                }
                recoveryTaskMapper.updateById(task);
                updateProgress(item, processed, event.comicIds().size(), event.taskId(), recovered, skipped, placeholder, errors);
            } catch (RuntimeException exception) {
                log.error("恢复漫画失败: taskId={}, comicId={}", event.taskId(), comicId, exception);
                errors++; processed++; task.setErrorComics(errors);
                task.setErrorMessage(exception.getMessage()); recoveryTaskMapper.updateById(task);
                updateProgress(item, processed, event.comicIds().size(), event.taskId(), recovered, skipped, placeholder, errors);
            }
        }
        task.setStatus(RecoveryTaskStatus.SUCCEEDED); task.setEndedAt(LocalDateTime.now());
        recoveryTaskMapper.updateById(task);
        syncItem(event.taskId(), ManagementTaskStatus.SUCCEEDED, null, RESULT_REF_TYPE, event.taskId());
        markProcessed(key);
    }

    private void updateProgress(ManagementTaskItemResponse item, int processed, int total, Long taskId,
            int recovered, int skipped, int placeholder, int errors) {
        if (item != null && total > 0) {
            int percent = Math.min(100, (recovered + skipped + placeholder + errors) * 100 / total);
            managementTaskService.updateItemProgress(item.getId(), 0, percent, STAGE_RECOVERY);
        }
    }

    public void processFailed(RecoveryFailedEvent event) {
        String key = EVENT_KEY_PREFIX + event.eventId();
        if (isProcessed(key)) {
            return;
        }
        RecoveryTask task = recoveryTaskMapper.selectById(event.taskId());
        if (task == null || TERMINAL_STATUSES.contains(task.getStatus())) { markProcessed(key); return; }
        task.setStatus(RecoveryTaskStatus.FAILED); task.setEndedAt(LocalDateTime.now());
        task.setErrorMessage(event.errorMessage()); recoveryTaskMapper.updateById(task);
        syncItem(event.taskId(), ManagementTaskStatus.FAILED, event.errorMessage(), RESULT_REF_TYPE, event.taskId());
        markProcessed(key);
    }

    public void markProcessingFailure(Long taskId, Exception exception) {
        try {
            RecoveryTask task = recoveryTaskMapper.selectById(taskId);
            if (task != null && !TERMINAL_STATUSES.contains(task.getStatus())) {
                task.setStatus(RecoveryTaskStatus.FAILED); task.setEndedAt(LocalDateTime.now());
                task.setErrorMessage("事件处理异常: " + exception.getMessage());
                recoveryTaskMapper.updateById(task);
                syncItem(taskId, ManagementTaskStatus.FAILED, task.getErrorMessage(), RESULT_REF_TYPE, taskId);
            }
        } catch (RuntimeException updateException) {
            log.error("标记恢复任务失败时出错, taskId={}", taskId, updateException);
        }
    }

    private ManagementTaskItemResponse syncItem(Long taskId, ManagementTaskStatus status,
            String errorMessage, String refType, Long refId) {
        var item = managementTaskService.findActiveItem(TARGET_TYPE_SYSTEM, taskId, TaskType.RECOVERY);
        return item == null ? null : managementTaskService.updateItemStatus(item.getId(), status,
                errorMessage, refType, refId);
    }

    private boolean isProcessed(String key) {
        try { return Boolean.TRUE.equals(redisTemplate.hasKey(key)); }
        catch (RuntimeException exception) { log.warn("幂等标记读取失败: key={}", key, exception); return false; }
    }

    private void markProcessed(String key) {
        try { redisTemplate.opsForValue().set(key, "1", IDEMPOTENCY_TTL); }
        catch (RuntimeException exception) { log.warn("幂等标记写入失败: key={}", key, exception); }
    }
}
