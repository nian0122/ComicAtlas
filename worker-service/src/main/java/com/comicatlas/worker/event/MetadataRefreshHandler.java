package com.comicatlas.worker.event;

import com.comicatlas.common.constant.MqQueues;
import com.comicatlas.common.event.MetadataRefreshEvent;
import com.comicatlas.common.mq.MqConsumerSupport;
import com.comicatlas.worker.export.MetadataJsonExporter;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
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

@Slf4j
@Component
@RequiredArgsConstructor
public class MetadataRefreshHandler {

    private final MetadataJsonExporter metadataJsonExporter;
    @Value("${worker.manga-root}")
    private String mangaRoot;
    private final MqConsumerSupport mqConsumerSupport;

    @RabbitListener(queues = MqQueues.METADATA_REFRESH)
    public void handle(MetadataRefreshEvent event,
            Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long tag) {
        Long comicId = event.comicId();
        mqConsumerSupport.consume(channel, tag, "元数据刷新: comicId=" + comicId, () -> {
            log.info("收到 metadata 刷新请求: comicId={}", comicId);
            String metadataJson = metadataJsonExporter.exportJson(comicId);
            Path metadataDir = Path.of(mangaRoot, "metadata");
            Files.createDirectories(metadataDir);
            Path metadataFile = metadataDir.resolve(comicId + ".json");
            writeAtomically(metadataFile, metadataJson);
            long fileSize = Files.size(metadataFile);
            log.info("metadata.json 写入完成: comicId={}, path={}, size={} bytes",
                    comicId, metadataFile, fileSize);
        });
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
