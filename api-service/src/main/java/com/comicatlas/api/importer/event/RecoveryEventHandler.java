package com.comicatlas.api.importer.event;

import com.comicatlas.api.admin.dto.RecoveryProgress;
import com.comicatlas.api.admin.recovery.RecoveryEngine;
import com.comicatlas.api.importer.entity.RecoveryTask;
import com.comicatlas.api.importer.mapper.RecoveryTaskMapper;
import com.comicatlas.common.event.ComicEvent;
import com.comicatlas.common.event.RecoveryFailedEvent;
import com.comicatlas.common.event.RecoveryScanCompletedEvent;
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
import java.util.Set;

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

    private final RecoveryEngine recoveryEngine;
    private final RecoveryTaskMapper recoveryTaskMapper;
    private final RedisTemplate<String, Object> redisTemplate;

    private static final Set<String> TERMINAL_STATUSES = Set.of("SUCCESS", "FAILED");

    @RabbitListener(queues = "recovery.result.queue")
    public void handle(ComicEvent event,
                       Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long tag) {

        if (event instanceof RecoveryScanCompletedEvent scanEvent) {
            handleScanCompleted(scanEvent, channel, tag);
        } else if (event instanceof RecoveryFailedEvent failEvent) {
            handleFailed(failEvent, channel, tag);
        } else {
            log.warn("recovery.result.queue 收到未知事件类型: {}, ack 跳过",
                    event.getClass().getSimpleName());
            ack(channel, tag);
        }
    }

    // ======================== 扫描完成处理 ========================

    private void handleScanCompleted(RecoveryScanCompletedEvent event,
                                     Channel channel, long tag) {
        String idempKey = "mq:event:" + event.eventId();
        Long taskId = event.taskId();
        log.info("RecoveryEventHandler: 收到扫描完成事件, taskId={}, comicCount={}",
                taskId, event.comicIds().size());

        try {
            // 幂等检查
            if (isEventProcessed(idempKey)) {
                log.info("事件已处理，ack: eventId={}", event.eventId());
                ack(channel, tag);
                return;
            }

            RecoveryTask task = recoveryTaskMapper.selectById(taskId);
            if (task == null) {
                log.warn("恢复任务不存在: taskId={}", taskId);
                markEventProcessed(idempKey);
                ack(channel, tag);
                return;
            }

            // 终态检查：已 SUCCESS/FAILED 的任务不再处理（应对重试产生的新 PENDING 任务会走新事件）
            if (TERMINAL_STATUSES.contains(task.getStatus())) {
                log.info("任务已处终态，跳过: taskId={}, status={}", taskId, task.getStatus());
                markEventProcessed(idempKey);
                ack(channel, tag);
                return;
            }

            // 标记 RUNNING
            task.setStatus("RUNNING");
            task.setStartedAt(LocalDateTime.now());
            task.setTotalComics(event.comicIds().size());
            task.setRecoveredComics(0);
            task.setSkippedComics(0);
            task.setPlaceholderComics(0);
            task.setErrorComics(0);
            task.setErrorMessage(null);
            task.setErrorDetails(null);
            recoveryTaskMapper.updateById(task);

            // 逐本恢复
            int totalSoFar = 0;
            int recovered = 0, skipped = 0, placeholder = 0, errors = 0;

            for (Long comicId : event.comicIds()) {
                try {
                    RecoveryProgress progress = recoveryEngine.processComicDir(comicId, totalSoFar);
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

                    log.debug("恢复进度: taskId={}, comicId={}, total={}, recovered={}, skipped={}, placeholder={}, error={}",
                            taskId, comicId, totalSoFar, recovered, skipped, placeholder, errors);
                } catch (Exception e) {
                    // 单个 comic 处理异常不中断整个任务，记录并继续
                    log.error("恢复漫画失败: taskId={}, comicId={}", taskId, comicId, e);
                    errors++;
                    totalSoFar++;
                    task.setErrorComics(errors);
                    task.setErrorMessage(e.getMessage());
                    recoveryTaskMapper.updateById(task);
                }
            }

            // 全部处理完成
            task.setStatus("SUCCESS");
            task.setEndedAt(LocalDateTime.now());
            recoveryTaskMapper.updateById(task);

            markEventProcessed(idempKey);
            ack(channel, tag);

            log.info("RecoveryEventHandler: 恢复完成, taskId={}, total={}, recovered={}, skipped={}, placeholder={}, error={}",
                    taskId, totalSoFar, recovered, skipped, placeholder, errors);

        } catch (Exception e) {
            log.error("RecoveryEventHandler: 扫描完成处理失败, taskId={}", taskId, e);
            // 尝试将任务标记为 FAILED
            try {
                RecoveryTask task = recoveryTaskMapper.selectById(taskId);
                if (task != null && !TERMINAL_STATUSES.contains(task.getStatus())) {
                    task.setStatus("FAILED");
                    task.setEndedAt(LocalDateTime.now());
                    task.setErrorMessage("事件处理异常: " + e.getMessage());
                    recoveryTaskMapper.updateById(task);
                }
            } catch (Exception updateEx) {
                log.error("RecoveryEventHandler: 标记任务失败时出错, taskId={}", taskId, updateEx);
            }
            reject(channel, tag);
        }
    }

    // ======================== 基础设施故障处理 ========================

    private void handleFailed(RecoveryFailedEvent event, Channel channel, long tag) {
        String idempKey = "mq:event:" + event.eventId();
        Long taskId = event.taskId();
        log.warn("RecoveryEventHandler: 收到失败事件, taskId={}, error={}",
                taskId, event.errorMessage());

        try {
            if (isEventProcessed(idempKey)) {
                ack(channel, tag);
                return;
            }

            RecoveryTask task = recoveryTaskMapper.selectById(taskId);
            if (task == null) {
                markEventProcessed(idempKey);
                ack(channel, tag);
                return;
            }

            if (TERMINAL_STATUSES.contains(task.getStatus())) {
                log.info("任务已处终态，跳过失败事件: taskId={}, status={}", taskId, task.getStatus());
                markEventProcessed(idempKey);
                ack(channel, tag);
                return;
            }

            task.setStatus("FAILED");
            task.setEndedAt(LocalDateTime.now());
            task.setErrorMessage(event.errorMessage());
            recoveryTaskMapper.updateById(task);

            markEventProcessed(idempKey);
            ack(channel, tag);
        } catch (Exception e) {
            log.error("RecoveryEventHandler: 失败事件处理异常, taskId={}", taskId, e);
            reject(channel, tag);
        }
    }

    // ======================== 工具方法 ========================

    private boolean isEventProcessed(String idempKey) {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(idempKey));
        } catch (Exception e) {
            log.warn("幂等标记读取失败，降级允许处理: key={}", idempKey, e);
            return false;
        }
    }

    private void markEventProcessed(String idempKey) {
        try {
            redisTemplate.opsForValue().set(idempKey, "1", Duration.ofDays(1));
        } catch (Exception e) {
            log.warn("幂等标记写入失败: key={}", idempKey, e);
        }
    }

    private void ack(Channel channel, long tag) {
        try {
            channel.basicAck(tag, false);
        } catch (Exception e) {
            log.error("消息 ack 失败: tag={}", tag, e);
        }
    }

    private void reject(Channel channel, long tag) {
        try {
            channel.basicReject(tag, false);
        } catch (Exception e) {
            log.error("消息 reject 失败: tag={}", tag, e);
        }
    }
}
