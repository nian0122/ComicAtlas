package com.comicatlas.worker.event;

import com.comicatlas.common.event.RecoveryFailedEvent;
import com.comicatlas.common.event.RecoveryRequestedEvent;
import com.comicatlas.common.event.RecoveryScanCompletedEvent;
import com.comicatlas.worker.config.WorkerConfig;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * 恢复任务处理器 — Worker 侧入口。
 * <p>
 * 监听 {@code recovery.task.queue}，收到 {@link RecoveryRequestedEvent} 后扫描 HQ 目录，
 * 收集所有数字命名的漫画目录 ID，发布 {@link RecoveryScanCompletedEvent} 到
 * {@code comic.recovery} 交换器（路由键 {@code recovery.progress}），由 API 侧的
 * {@code RecoveryEventHandler} 消费后逐本调用 {@code RecoveryEngine} 完成 DB 恢复。
 * <p>
 * <strong>Worker 绝不直接读写 MySQL</strong> — 所有 DB 操作由 API 侧事件处理器完成。
 * <p>
 * 基础设施故障（HQ 根目录不可读 → 直接发布 {@link RecoveryFailedEvent}）。
 *
 * @see com.comicatlas.common.event.RecoveryScanCompletedEvent
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RecoveryTaskHandler {

    private final WorkerConfig config;
    private final RabbitTemplate rabbitTemplate;

    @RabbitListener(queues = "recovery.task.queue")
    public void handle(RecoveryRequestedEvent event,
                       Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long tag) {
        Long taskId = event.taskId();
        log.info("RecoveryTaskHandler: 接收恢复请求, taskId={}", taskId);

        Path hqRoot = Path.of(config.getMangaRoot(), "hq");

        // 基础设施检查：HQ 根目录必须可读
        if (!Files.isDirectory(hqRoot) || !Files.isReadable(hqRoot)) {
            String errorMsg = "HQ 根目录不可读: " + hqRoot.toAbsolutePath();
            log.error("RecoveryTaskHandler: {}", errorMsg);
            publishFailed(taskId, errorMsg);
            ack(channel, tag);
            return;
        }

        List<Long> comicIds = new ArrayList<>();
        try (var stream = Files.newDirectoryStream(hqRoot)) {
            for (Path dir : stream) {
                if (!Files.isDirectory(dir)) continue;
                String dirName = dir.getFileName().toString();

                // 只收集纯数字目录名（数字目录名 = comicId）
                try {
                    long comicId = Long.parseLong(dirName);
                    if (comicId > 0) {
                        comicIds.add(comicId);
                    }
                } catch (NumberFormatException ignored) {
                    log.debug("RecoveryTaskHandler: 跳过非数字目录: {}", dirName);
                }
            }
        } catch (Exception e) {
            log.error("RecoveryTaskHandler: 扫描 HQ 目录失败, taskId={}", taskId, e);
            publishFailed(taskId, "扫描 HQ 目录失败: " + e.getMessage());
            ack(channel, tag);
            return;
        }

        // 自然排序（按 ID 从小到大）
        comicIds.sort(Comparator.naturalOrder());
        log.info("RecoveryTaskHandler: 扫描完成, taskId={}, 发现 {} 个漫画目录", taskId, comicIds.size());

        // 发布扫描结果事件（路由键 recovery.progress → API recovery.result.queue）
        var scanEvent = new RecoveryScanCompletedEvent(
                UUID.randomUUID(), Instant.now(), taskId, comicIds);
        rabbitTemplate.convertAndSend("comic.recovery", "recovery.progress", scanEvent);
        log.info("RecoveryTaskHandler: 已发布 RecoveryScanCompletedEvent, taskId={}", taskId);

        ack(channel, tag);
    }

    private void publishFailed(Long taskId, String errorMessage) {
        var failEvent = new RecoveryFailedEvent(
                UUID.randomUUID(), Instant.now(), taskId, errorMessage);
        rabbitTemplate.convertAndSend("comic.recovery", "recovery.failed", failEvent);
        log.info("RecoveryTaskHandler: 已发布 RecoveryFailedEvent, taskId={}, error={}", taskId, errorMessage);
    }

    private void ack(Channel channel, long tag) {
        try {
            channel.basicAck(tag, false);
        } catch (Exception e) {
            log.error("RecoveryTaskHandler: ack 失败, tag={}", tag, e);
        }
    }
}
