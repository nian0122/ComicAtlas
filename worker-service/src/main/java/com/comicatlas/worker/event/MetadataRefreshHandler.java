package com.comicatlas.worker.event;

import com.comicatlas.common.event.MetadataRefreshEvent;
import com.comicatlas.worker.export.ExportCollectResult;
import com.comicatlas.worker.export.ExportCollector;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

@Slf4j
@Component
@RequiredArgsConstructor
public class MetadataRefreshHandler {

    private final ExportCollector exportCollector;
    @Value("${manga.root}")
    private String mangaRoot;

    @RabbitListener(queues = "metadata.refresh.queue")
    public void handle(MetadataRefreshEvent event,
            Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long tag) {
        log.info("收到 metadata 刷新请求: comicId={}", event.comicId());

        try {
            ExportCollectResult result = exportCollector.collect(event.comicId());
            String metadataJson = result.metadataJson();

            Path metadataDir = Path.of(mangaRoot, "metadata");
            Files.createDirectories(metadataDir);
            Path metadataFile = metadataDir.resolve(event.comicId() + ".json");
            Files.writeString(metadataFile, metadataJson, StandardCharsets.UTF_8);

            long fileSize = Files.size(metadataFile);
            log.info("metadata.json 写入完成: comicId={}, path={}, size={} bytes",
                    event.comicId(), metadataFile, fileSize);

            channel.basicAck(tag, false);
        } catch (Exception e) {
            log.error("metadata 刷新失败: comicId={}", event.comicId(), e);
            try {
                channel.basicNack(tag, false, false);
            } catch (Exception ex) {
                log.warn("消息 nack 失败: tag={}, comicId={}", tag, event.comicId(), ex);
            }
        }
    }
}
