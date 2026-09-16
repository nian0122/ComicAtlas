package com.comicatlas.worker.media.event;
import com.comicatlas.common.constant.MqQueues;
import com.comicatlas.common.event.MetadataRefreshEvent;
import com.comicatlas.common.mq.MqConsumerSupport;
import com.comicatlas.common.metadata.file.MetadataFileWriter;
import com.comicatlas.worker.shared.metadata.MetadataExporter;
import com.comicatlas.worker.config.WorkerConfig;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

@Slf4j
@Component
@RequiredArgsConstructor
/**
 * 消费元数据刷新请求的 Worker 处理器。
 * <p>根据数据库当前内容生成 metadata JSON，并通过临时文件加原子移动写入元数据目录，
 * 不改变漫画、章节和页面的生命周期状态；写入失败交由消息消费策略处理。</p>
 */
public class MetadataRefreshHandler {

    private final MetadataExporter metadataJsonExporter;
    private final WorkerConfig workerConfig;
    private final MqConsumerSupport mqConsumerSupport;

    /**
     * 生成并原子替换指定漫画的 metadata 文件。
     *
     * @param event 指定待刷新的漫画
     * @param channel 当前 RabbitMQ 通道
     * @param tag 当前消息投递标签
     */
    @RabbitListener(queues = MqQueues.METADATA_REFRESH)
    public void handle(MetadataRefreshEvent event,
            Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long tag) {
        Long comicId = event.comicId();
        mqConsumerSupport.consume(channel, tag, "元数据刷新: comicId=" + comicId, () -> {
            log.info("收到 metadata 刷新请求: comicId={}", comicId);
            String metadataJson = metadataJsonExporter.exportJson(comicId);
            Path metadataDir = workerConfig.resolveMetadataDir();
            Files.createDirectories(metadataDir);
            Path metadataFile = metadataDir.resolve(comicId + ".json");
            // 统一原子写（tmp → flush → ATOMIC_MOVE）：读者永不见半截 JSON，
            // 失败向上抛出交由 MqConsumerSupport reject/DLQ，不吞异常
            MetadataFileWriter.write(metadataFile, metadataJson);
            long fileSize = Files.size(metadataFile);
            log.info("metadata.json 写入完成: comicId={}, fileName={}, size={} bytes",
                    comicId, metadataFile.getFileName(), fileSize);
        });
    }
}
