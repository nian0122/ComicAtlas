package com.comicatlas.worker.event;

import com.comicatlas.common.constant.MqExchanges;
import com.comicatlas.common.constant.MqQueues;
import com.comicatlas.common.constant.MqRoutingKeys;
import com.comicatlas.common.event.DeleteHqRequestedEvent;
import com.comicatlas.common.event.HqDeletedEvent;
import com.comicatlas.common.mq.MqConsumerSupport;
import com.comicatlas.worker.entity.ExportMedia;
import com.comicatlas.worker.storage.StorageProperties;
import com.comicatlas.worker.storage.StorageRoot;
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
    private final MqConsumerSupport mqConsumerSupport;

    @RabbitListener(queues = MqQueues.HQ_DELETE)
    public void handle(DeleteHqRequestedEvent event,
            Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long tag) {
        Long comicId = event.comicId();
        Long chapterId = event.chapterId();

        mqConsumerSupport.consume(channel, tag, "HQ删除: chapterId=" + chapterId, () -> {
            log.info("HQ 删除开始: comicId={}, chapterId={}", comicId, chapterId);

            AtomicLong freedBytes = new AtomicLong(0);
            AtomicInteger deletedCount = new AtomicInteger(0);

            // 仅删除 IMAGE：VIDEO 不参与 HQ 删除（F6-26 修复前遍历全部媒体导致视频文件被删而 DB 仍 READY）
            List<ExportMedia> pages = mediaMapper.selectByChapterId(chapterId).stream()
                    .filter(m -> "IMAGE".equals(m.getMediaType()))
                    .toList();
            if (pages.isEmpty()) {
                // 无 IMAGE 可删（纯视频章节/无页面）：VIDEO 保留，回传空完成事件保持 API 一致性
                log.info("HQ 删除：章节无 IMAGE 页面（VIDEO 保留），回传空完成: chapterId={}", chapterId);
                HqDeletedEvent noop = new HqDeletedEvent(
                        UUID.randomUUID(), Instant.now(), comicId, chapterId, 0L, 0);
                rabbitTemplate.convertAndSend(MqExchanges.IMAGE, MqRoutingKeys.HQ_DELETE_COMPLETED, noop);
                return;
            }

            StorageRoot hqRoot = storageProperties.getRoots().get("HQ");
            if (hqRoot == null) {
                throw new IllegalStateException("HQ 存储根未配置");
            }

            for (ExportMedia page : pages) {
                if (page.getHqPath() == null || page.getHqPath().isBlank()) { continue; }
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
                if (Files.exists(chapterDir)) { Files.deleteIfExists(chapterDir); }
                if (event.chapterNo() != null && !event.chapterNo().isBlank()) {
                    Path oldDir = hqRoot.resolve(comicId + "/" + event.chapterNo());
                    if (Files.exists(oldDir)) { Files.deleteIfExists(oldDir); }
                }
            } catch (IOException e) {
                // 目录非空（VIDEO 仍存在）或删除失败：不递归删除、不视为任务失败
                log.warn("HQ 删除章节目录失败（非致命，VIDEO 保留时目录非空属正常）: chapterId={}", chapterId, e);
            }

            HqDeletedEvent completedEvent = new HqDeletedEvent(
                    UUID.randomUUID(), Instant.now(),
                    comicId, chapterId, freedBytes.get(), deletedCount.get());
            rabbitTemplate.convertAndSend(MqExchanges.IMAGE, MqRoutingKeys.HQ_DELETE_COMPLETED, completedEvent);
            log.info("HQ 删除完成: comicId={}, chapterId={}, freedBytes={}, deletedCount={}",
                    comicId, chapterId, freedBytes.get(), deletedCount.get());
        });
    }
}
