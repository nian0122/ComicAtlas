package com.comicatlas.worker.event;

import com.comicatlas.common.constant.MqExchanges;
import com.comicatlas.common.constant.MqQueues;
import com.comicatlas.common.constant.MqRoutingKeys;
import com.comicatlas.common.constant.StorageRootKeys;
import com.comicatlas.common.event.RecoveryFailedEvent;
import com.comicatlas.common.event.RecoveryRequestedEvent;
import com.comicatlas.common.event.RecoveryScanCompletedEvent;
import com.comicatlas.common.mq.MqConsumerSupport;
import com.comicatlas.worker.storage.StorageProperties;
import com.comicatlas.worker.storage.StorageRoot;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.nio.file.DirectoryStream;
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
 * 监听 {@link MqQueues#RECOVERY_TASK}，收到 {@link RecoveryRequestedEvent} 后扫描 HQ 目录，
 * 收集所有数字命名的漫画目录 ID，发布 {@link RecoveryScanCompletedEvent} 到
 * {@link MqExchanges#RECOVERY} 交换器（路由键 {@link MqRoutingKeys#RECOVERY_PROGRESS}），由 API 侧的
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

    private final StorageProperties storageProperties;
    private final RabbitTemplate rabbitTemplate;
    private final MqConsumerSupport mqConsumerSupport;

    @RabbitListener(queues = MqQueues.RECOVERY_TASK)
    public void handle(RecoveryRequestedEvent event, Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long tag) {
        Long taskId = event.taskId();
        log.info("接收恢复请求: taskId={}", taskId);
        mqConsumerSupport.consume(channel, tag, "存储恢复: taskId=" + taskId,
                () -> scanAndPublish(taskId),
                e -> publishFailed(taskId, e.getMessage()),
                MqConsumerSupport.FailurePolicy.ACK_AFTER_CALLBACK);
    }

    /** 扫描 HQ 根目录收集数字命名的漫画目录 ID，排序后发布扫描完成事件。 */
    private void scanAndPublish(Long taskId) throws Exception {
        StorageRoot hqRoot = storageProperties.getRoots().get(StorageRootKeys.HQ);
        Path hqRootPath = hqRoot == null ? null : hqRoot.getPath();
        if (hqRootPath == null || !Files.isDirectory(hqRootPath) || !Files.isReadable(hqRootPath)) {
            throw new IllegalStateException("HQ 根目录不可读: "
                    + (hqRootPath == null ? "存储根未配置" : hqRootPath.toAbsolutePath()));
        }
        List<Long> comicIds = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(hqRootPath)) {
            for (Path dir : stream) {
                if (!Files.isDirectory(dir)) {
                    continue;
                }
                String dirName = dir.getFileName().toString();
                try {
                    long comicId = Long.parseLong(dirName);
                    if (comicId > 0) {
                        comicIds.add(comicId);
                    }
                } catch (NumberFormatException ignored) {
                    log.debug("跳过非数字目录: {}", dirName);
                }
            }
        }
        comicIds.sort(Comparator.naturalOrder());
        log.info("扫描完成: taskId={}, 发现 {} 个漫画目录", taskId, comicIds.size());
        rabbitTemplate.convertAndSend(MqExchanges.RECOVERY, MqRoutingKeys.RECOVERY_PROGRESS,
                new RecoveryScanCompletedEvent(UUID.randomUUID(), Instant.now(), taskId, comicIds));
    }

    private void publishFailed(Long taskId, String errorMessage) {
        RecoveryFailedEvent failEvent = new RecoveryFailedEvent(
                UUID.randomUUID(), Instant.now(), taskId, errorMessage);
        rabbitTemplate.convertAndSend(MqExchanges.RECOVERY, MqRoutingKeys.RECOVERY_FAILED, failEvent);
        log.info("已发布恢复失败事件: taskId={}, error={}", taskId, errorMessage);
    }
}
