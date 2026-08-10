package com.comicatlas.worker.event;

import com.comicatlas.common.constant.MqExchanges;
import com.comicatlas.common.constant.MqQueues;
import com.comicatlas.common.constant.MqRoutingKeys;
import com.comicatlas.common.event.ImportMetadataRefreshCompletedEvent;
import com.comicatlas.common.event.ImportMetadataRefreshFailedEvent;
import com.comicatlas.common.event.MetadataRefreshEvent;
import com.comicatlas.common.mq.MqConsumerSupport;
import com.comicatlas.worker.export.MetadataJsonExporter;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class MetadataRefreshHandler {

    private static final String SANITIZED_ROOT = "{MANGA_ROOT}";

    private final MetadataJsonExporter metadataJsonExporter;
    @Value("${worker.manga-root}")
    private String mangaRoot;
    private final MqConsumerSupport mqConsumerSupport;
    private final RabbitTemplate rabbitTemplate;

    @RabbitListener(queues = MqQueues.METADATA_REFRESH)
    public void handle(MetadataRefreshEvent event,
            Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long tag) {
        Long comicId = event.comicId();
        Long taskId = event.taskId();
        if (taskId != null) {
            // 导入最终化收尾触发：重建成功后回传结果事件，API 据此置 comic/task 终态。
            // 失败不 reject（文件为原子写入，旧 JSON 完好），改发 failed 结果事件保持可重试。
            mqConsumerSupport.consume(channel, tag, "元数据刷新(导入收尾): comicId=" + comicId + ", taskId=" + taskId,
                    () -> {
                        refresh(comicId);
                        rabbitTemplate.convertAndSend(MqExchanges.IMPORT, MqRoutingKeys.IMPORT_METADATA_REFRESH_COMPLETED,
                                new ImportMetadataRefreshCompletedEvent(UUID.randomUUID(), Instant.now(), taskId, comicId));
                        log.info("导入元数据重建完成，已发布 completed: taskId={}, comicId={}", taskId, comicId);
                    },
                    e -> {
                        rabbitTemplate.convertAndSend(MqExchanges.IMPORT, MqRoutingKeys.IMPORT_METADATA_REFRESH_FAILED,
                                new ImportMetadataRefreshFailedEvent(UUID.randomUUID(), Instant.now(), taskId, comicId,
                                        "METADATA_REFRESH_FAILED", sanitize(e.getMessage())));
                        log.warn("导入元数据重建失败，已发布 failed: taskId={}, comicId={}", taskId, comicId, e);
                    },
                    MqConsumerSupport.FailurePolicy.ACK_AFTER_CALLBACK);
            return;
        }
        // 旧调用点（taskId 为 null）：仅重建并 ACK，不发结果事件
        mqConsumerSupport.consume(channel, tag, "元数据刷新: comicId=" + comicId, () -> refresh(comicId));
    }

    private void refresh(Long comicId) throws IOException {
        log.info("收到 metadata 刷新请求: comicId={}", comicId);
        String metadataJson = metadataJsonExporter.exportJson(comicId);
        Path metadataDir = Path.of(mangaRoot, "metadata");
        Files.createDirectories(metadataDir);
        Path metadataFile = metadataDir.resolve(comicId + ".json");
        writeAtomically(metadataFile, metadataJson);
        long fileSize = Files.size(metadataFile);
        log.info("metadata.json 写入完成: comicId={}, path={}, size={} bytes",
                comicId, metadataFile, fileSize);
    }

    /** 脱敏：剔除本地存储根绝对路径（与 RecoveryTaskHandler 等失败消息一致）。 */
    private String sanitize(String message) {
        if (message == null) {
            return "unknown error";
        }
        return message.replace(mangaRoot, SANITIZED_ROOT);
    }

    /**
     * 原子写入 metadata.json：先写同目录临时文件（flush + close），成功后以
     * ATOMIC_MOVE 替换目标文件，保证读者永远只能看到旧版本或完整新版本。
     * 任何失败都会清理临时文件并向上抛出，交由 {@link MqConsumerSupport} reject/DLQ，不吞异常。
     *
     * @param target  目标 metadata 文件
     * @param content JSON 内容
     * @throws IOException 临时写入或原子移动失败时抛出
     */
    private static void writeAtomically(Path target, String content) throws IOException {
        Path tempFile = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            try (BufferedWriter writer = Files.newBufferedWriter(tempFile, StandardCharsets.UTF_8)) {
                writer.write(content);
                writer.flush();
            }
            try {
                Files.move(tempFile, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                throw new IOException("原子移动不受支持，拒绝非原子覆盖写入: " + target, e);
            }
        } finally {
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException e) {
                log.warn("metadata 临时文件清理失败: {}", tempFile, e);
            }
        }
    }
}
