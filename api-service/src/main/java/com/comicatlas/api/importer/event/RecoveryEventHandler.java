package com.comicatlas.api.importer.event;

import com.comicatlas.api.recovery.dto.RecoveryProgressVO;
import com.comicatlas.api.recovery.RecoveryEngine;
import com.comicatlas.api.importer.entity.RecoveryTask;
import com.comicatlas.api.importer.mapper.RecoveryTaskMapper;
import com.comicatlas.api.management.dto.ManagementTaskItemResponse;
import com.comicatlas.api.management.entity.ManagementTaskItem;
import com.comicatlas.api.management.service.ManagementTaskService;
import com.comicatlas.common.constant.MqQueues;
import com.comicatlas.common.event.ComicEvent;
import com.comicatlas.common.event.RecoveryFailedEvent;
import com.comicatlas.common.event.RecoveryScanCompletedEvent;
import com.comicatlas.common.mq.MqConsumerSupport;
import com.comicatlas.api.management.enums.ManagementTaskStatus;
import com.comicatlas.api.recovery.enums.RecoveryTaskStatus;
import com.comicatlas.api.management.enums.TaskType;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.EnumSet;

/**
 * 恢复事件处理器 — API 侧消费恢复结果事件，调度 {@link RecoveryEngine} 完成 DB 恢复。
 * <p>
 * 监听 {@code recovery.result.queue}，接收三种结果事件：
 * <ul>
 *   <li>{@link RecoveryScanCompletedEvent} — Worker 目录扫描完成，逐个处理漫画目录</li>
 *   <li>{@link RecoveryFailedEvent} — Worker 基础设施故障，将任务标记为 FAILED</li>
 * </ul>
 * <p>
 * 状态机：PENDING → RUNNING → SUCCESS | FAILED<br>
 * 幂等性：通过 Redis 键 {@code mq:event:{eventId}} 防止重复消费。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RecoveryEventHandler {

    /** Redis 事件幂等标记 key 前缀。 */
    private static final String EVENT_IDEMPOTENCY_KEY_PREFIX = "mq:event:";
    /** Redis 幂等标记保留时长。 */
    private static final Duration IDEMPOTENCY_TTL = Duration.ofDays(1);
    /** 统一任务目标类型：系统级（恢复任务）。 */
    private static final String TARGET_TYPE_SYSTEM = "SYSTEM";
    /** 统一任务项结果引用类型：恢复任务。 */
    private static final String RESULT_REF_TYPE_RECOVERY_TASK = "RECOVERY_TASK";
    /** 恢复进度阶段标识。 */
    private static final String STAGE_RECOVERY = "RECOVERY";

    private final RecoveryEngine recoveryEngine;
    private final RecoveryTaskMapper recoveryTaskMapper;
    private final RedisTemplate<String, Object> redisTemplate;
    private final ManagementTaskService managementTaskService;
    private final MqConsumerSupport mqConsumerSupport;

    private static final EnumSet<RecoveryTaskStatus> TERMINAL_STATUSES = EnumSet.of(
            RecoveryTaskStatus.SUCCEEDED, RecoveryTaskStatus.FAILED, RecoveryTaskStatus.CANCELLED);

    @RabbitListener(queues = MqQueues.RECOVERY_RESULT)
    public void handle(ComicEvent event,
                       Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long tag) {

        if (event instanceof RecoveryScanCompletedEvent scanEvent) {
            handleScanCompleted(scanEvent, channel, tag);
        } else if (event instanceof RecoveryFailedEvent failEvent) {
            handleFailed(failEvent, channel, tag);
        } else {
            log.warn("recovery.result.queue 收到未知事件类型: {}, ack 跳过",
                    event.getClass().getSimpleName());
            mqConsumerSupport.consume(channel, tag, "未知恢复事件", () -> { });
        }
    }

    // ======================== 扫描完成处理 ========================

    private void handleScanCompleted(RecoveryScanCompletedEvent event,
                                     Channel channel, long tag) {
        String idempotencyKey = EVENT_IDEMPOTENCY_KEY_PREFIX + event.eventId();
        Long taskId = event.taskId();
        log.info("收到扫描完成事件, taskId={}, comicCount={}",
                taskId, event.comicIds().size());

        mqConsumerSupport.consume(channel, tag, "恢复扫描完成: taskId=" + taskId,
                () -> processScanCompleted(event, idempotencyKey, taskId),
                ex -> markRecoveryTaskFailed(taskId, ex),
                MqConsumerSupport.FailurePolicy.REJECT_TO_DLQ);
    }

    private void processScanCompleted(RecoveryScanCompletedEvent event, String idempotencyKey, Long taskId) {
        // 幂等检查
        if (isEventProcessed(idempotencyKey)) {
            log.info("事件已处理，ack: eventId={}", event.eventId());
            return;
        }

        RecoveryTask task = recoveryTaskMapper.selectById(taskId);
        if (task == null) {
            log.warn("恢复任务不存在: taskId={}", taskId);
            markEventProcessed(idempotencyKey);
            return;
        }

        // 终态检查：已 SUCCESS/FAILED 的任务不再处理（应对重试产生的新 PENDING 任务会走新事件）
        if (TERMINAL_STATUSES.contains(task.getStatus())) {
            log.info("任务已处终态，跳过: taskId={}, status={}", taskId, task.getStatus());
            markEventProcessed(idempotencyKey);
            return;
        }

        // 标记 RUNNING
        task.setStatus(RecoveryTaskStatus.RUNNING);
        task.setStartedAt(LocalDateTime.now());
        task.setTotalComics(event.comicIds().size());
        task.setRecoveredComics(0);
        task.setSkippedComics(0);
        task.setPlaceholderComics(0);
        task.setErrorComics(0);
        task.setErrorMessage(null);
        task.setErrorDetails(null);
        recoveryTaskMapper.updateById(task);

        // 同步统一任务项为 RUNNING
        ManagementTaskItemResponse managementItem = syncRecoveryItem(taskId, ManagementTaskStatus.RUNNING,
                null, null, 0L);

        // 逐本恢复
        int totalSoFar = 0;
        int recovered = 0;
        int skipped = 0;
        int placeholder = 0;
        int errors = 0;

        for (Long comicId : event.comicIds()) {
            try {
                RecoveryProgressVO progress = recoveryEngine.processComicDir(comicId, totalSoFar);
                totalSoFar = progress.totalComics();
                recovered += progress.recoveredComics();
                skipped += progress.skippedComics();
                placeholder += progress.placeholderComics();
                errors += progress.errorComics();

                // 每个漫画完成后更新计数器（前端轮询可见进度）
                task.setRecoveredComics(recovered);
                task.setSkippedComics(skipped);
                task.setPlaceholderComics(placeholder);
                task.setErrorComics(errors);
                if (progress.lastError() != null) {
                    task.setErrorMessage(progress.lastError());
                }
                recoveryTaskMapper.updateById(task);

                // 同步统一任务项进度（0-100）
                if (managementItem != null && totalSoFar > 0) {
                    int progressPercent = Math.min(100,
                            (recovered + skipped + placeholder + errors) * 100 / totalSoFar);
                    managementTaskService.updateItemProgress(managementItem.getId(), 0, progressPercent, STAGE_RECOVERY);
                }

                log.debug("恢复进度: taskId={}, comicId={}, total={}, recovered={}, skipped={}, placeholder={}, error={}",
                        taskId, comicId, totalSoFar, recovered, skipped, placeholder, errors);
            } catch (RuntimeException ex) {
                // 单个 comic 处理异常不中断整个任务，记录并继续
                log.error("恢复漫画失败: taskId={}, comicId={}", taskId, comicId, ex);
                errors++;
                totalSoFar++;
                task.setErrorComics(errors);
                task.setErrorMessage(ex.getMessage());
                recoveryTaskMapper.updateById(task);
            }
        }

        // 全部处理完成
        task.setStatus(RecoveryTaskStatus.SUCCEEDED);
        task.setEndedAt(LocalDateTime.now());
        recoveryTaskMapper.updateById(task);

        // 同步统一任务项为 SUCCEEDED
        syncRecoveryItem(taskId, ManagementTaskStatus.SUCCEEDED, null, RESULT_REF_TYPE_RECOVERY_TASK, taskId);

        markEventProcessed(idempotencyKey);

        log.info("恢复完成, taskId={}, total={}, recovered={}, skipped={}, placeholder={}, error={}",
                taskId, totalSoFar, recovered, skipped, placeholder, errors);
    }

    /** 处理失败时标记恢复任务为 FAILED 并同步管理项（原 catch 副作用，作为 onFailure 回调）。 */
    private void markRecoveryTaskFailed(Long taskId, Exception failure) {
        try {
            RecoveryTask task = recoveryTaskMapper.selectById(taskId);
            if (task != null && !TERMINAL_STATUSES.contains(task.getStatus())) {
                task.setStatus(RecoveryTaskStatus.FAILED);
                task.setEndedAt(LocalDateTime.now());
                task.setErrorMessage("事件处理异常: " + failure.getMessage());
                recoveryTaskMapper.updateById(task);
                // 同步统一任务项为 FAILED
                syncRecoveryItem(taskId, ManagementTaskStatus.FAILED,
                        task.getErrorMessage(), RESULT_REF_TYPE_RECOVERY_TASK, taskId);
            }
        } catch (RuntimeException updateEx) {
            log.error("标记任务失败时出错, taskId={}", taskId, updateEx);
        }
    }

    // ======================== 基础设施故障处理 ========================

    private void handleFailed(RecoveryFailedEvent event, Channel channel, long tag) {
        String idempotencyKey = EVENT_IDEMPOTENCY_KEY_PREFIX + event.eventId();
        Long taskId = event.taskId();
        log.warn("收到失败事件, taskId={}, error={}",
                taskId, event.errorMessage());

        mqConsumerSupport.consume(channel, tag, "恢复失败事件: taskId=" + taskId,
                () -> processFailed(event, idempotencyKey, taskId));
    }

    private void processFailed(RecoveryFailedEvent event, String idempotencyKey, Long taskId) {
        if (isEventProcessed(idempotencyKey)) {
            return;
        }

        RecoveryTask task = recoveryTaskMapper.selectById(taskId);
        if (task == null) {
            markEventProcessed(idempotencyKey);
            return;
        }

        if (TERMINAL_STATUSES.contains(task.getStatus())) {
            log.info("任务已处终态，跳过失败事件: taskId={}, status={}", taskId, task.getStatus());
            markEventProcessed(idempotencyKey);
            return;
        }

        task.setStatus(RecoveryTaskStatus.FAILED);
        task.setEndedAt(LocalDateTime.now());
        task.setErrorMessage(event.errorMessage());
        recoveryTaskMapper.updateById(task);

        // 同步统一任务项为 FAILED
        syncRecoveryItem(taskId, ManagementTaskStatus.FAILED,
                event.errorMessage(), RESULT_REF_TYPE_RECOVERY_TASK, taskId);

        markEventProcessed(idempotencyKey);
    }

    // ======================== 工具方法 ========================

    private boolean isEventProcessed(String idempotencyKey) {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(idempotencyKey));
        } catch (RuntimeException ex) {
            log.warn("幂等标记读取失败，降级允许处理: key={}", idempotencyKey, ex);
            return false;
        }
    }

    private void markEventProcessed(String idempotencyKey) {
        try {
            redisTemplate.opsForValue().set(idempotencyKey, "1", IDEMPOTENCY_TTL);
        } catch (RuntimeException ex) {
            log.warn("幂等标记写入失败: key={}", idempotencyKey, ex);
        }
    }

    /**
     * 同步统一恢复任务项状态；无活跃项时跳过（终态/旧事件幂等）。
     */
    private ManagementTaskItemResponse syncRecoveryItem(Long recoveryTaskId, ManagementTaskStatus status,
                                                        String errorMessage, String refType, Long refId) {
        ManagementTaskItem item = managementTaskService.findActiveItem(
                TARGET_TYPE_SYSTEM, recoveryTaskId, TaskType.RECOVERY);
        if (item != null) {
            return managementTaskService.updateItemStatus(item.getId(), status, errorMessage, refType, refId);
        }
        return null;
    }
}
