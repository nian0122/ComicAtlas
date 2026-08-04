package com.comicatlas.worker.event;

import com.comicatlas.common.event.DeleteHqRequestedEvent;
import com.comicatlas.common.event.HqDeletedEvent;
import com.comicatlas.worker.entity.ExportMedia;
import com.comicatlas.worker.file.storage.StorageProperties;
import com.comicatlas.worker.file.storage.StorageRoot;
import com.comicatlas.worker.mapper.ExportMediaMapper;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
@RequiredArgsConstructor
public class HqDeleteHandler {

    private final StorageProperties storageProperties;
    private final ExportMediaMapper mediaMapper;
    private final RabbitTemplate rabbitTemplate;

    @RabbitListener(queues = "hq.delete.queue")
    public void handle(DeleteHqRequestedEvent event,
            Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long tag) {
        Long comicId = event.comicId();
        Long chapterId = event.chapterId();

        log.info("HQ 删除开始: comicId={}, chapterId={}", comicId, chapterId);

        AtomicLong freedBytes = new AtomicLong(0);
        AtomicInteger deletedCount = new AtomicInteger(0);

        try {
            List<ExportMedia> pages = mediaMapper.selectByChapterId(chapterId);
            if (pages.isEmpty()) {
                log.warn("章节无页面数据: chapterId={}", chapterId);
                channel.basicAck(tag, false);
                return;
            }

            StorageRoot hqRoot = storageProperties.getRoots().get("HQ");
            if (hqRoot == null) {
                log.error("HQ 存储根未配置");
                channel.basicReject(tag, false);
                return;
            }

            for (ExportMedia page : pages) {
                if (page.getHqPath() == null || page.getHqPath().isBlank()) continue;
                Path filePath = hqRoot.resolve(page.getHqPath());
                try {
                    if (Files.exists(filePath)) {
                        long size = Files.size(filePath);
                        Files.delete(filePath);
                        freedBytes.addAndGet(size);
                        deletedCount.incrementAndGet();
                    }
                } catch (IOException e) {
                    log.error("删除文件失败: {}, 拒绝 ACK 触发重试", filePath, e);
                    throw new RuntimeException("删除文件失败: " + filePath, e);
                }
            }

            try {
                Path chapterDir = hqRoot.resolve(comicId + "/" + chapterId);
                if (Files.exists(chapterDir)) Files.deleteIfExists(chapterDir);
                if (event.chapterNo() != null && !event.chapterNo().isBlank()) {
                    Path oldDir = hqRoot.resolve(comicId + "/" + event.chapterNo());
                    if (Files.exists(oldDir)) Files.deleteIfExists(oldDir);
                }
            } catch (IOException e) {
                log.warn("删除空目录失败: chapterId={}", chapterId);
            }

            HqDeletedEvent completedEvent = new HqDeletedEvent(
                    UUID.randomUUID(), Instant.now(),
                    comicId, chapterId, freedBytes.get(), deletedCount.get());
            rabbitTemplate.convertAndSend("comic.image", "hq.delete.completed", completedEvent);
            channel.basicAck(tag, false);
            log.info("HQ 删除完成: comicId={}, chapterId={}, freedBytes={}, deletedCount={}",
                    comicId, chapterId, freedBytes.get(), deletedCount.get());
        } catch (Exception e) {
            log.error("HQ 删除失败: comicId={}, chapterId={}", comicId, chapterId, e);
            try { channel.basicReject(tag, false); } catch (Exception ex) { log.warn("消息 reject 失败: tag={}", tag, ex); }
        }
    }
}
