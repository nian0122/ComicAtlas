package com.comicatlas.worker.event;

import com.comicatlas.common.event.DeleteHqRequestedEvent;
import com.comicatlas.common.event.HqDeletedEvent;
import com.comicatlas.worker.common.FilePathBuilder;
import com.comicatlas.worker.config.WorkerConfig;
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
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
@RequiredArgsConstructor
public class HqDeleteHandler {

    private final WorkerConfig config;
    private final FilePathBuilder pathBuilder;
    private final RabbitTemplate rabbitTemplate;

    private static final Set<String> IMAGE_EXTENSIONS = new HashSet<>(Set.of(
        ".jpg", ".jpeg", ".png", ".webp", ".gif"
    ));

    @RabbitListener(queues = "hq.delete.queue")
    public void handle(DeleteHqRequestedEvent event,
            Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long tag) {
        Long comicId = event.comicId();
        Long chapterId = event.chapterId();
        String chapterNo = event.chapterNo();

        if (chapterNo == null || chapterNo.isBlank()) {
            log.error("chapterNo 为空，无法删除 HQ: comicId={}, chapterId={}", comicId, chapterId);
            try { channel.basicReject(tag, false); } catch (Exception ignored) {}
            return;
        }

        log.info("HQ 删除开始: comicId={}, chapterId={}, chapterNo={}",
                comicId, chapterId, chapterNo);

        String hqDir = Path.of(config.getMangaRoot(),
                pathBuilder.hqDir(comicId, chapterNo)).toString();

        AtomicLong freedBytes = new AtomicLong(0);
        AtomicInteger deletedCount = new AtomicInteger(0);

        try {
            Path dirPath = Path.of(hqDir);
            if (!Files.exists(dirPath)) {
                log.warn("HQ 目录不存在: {}", hqDir);
            } else {
                try (var stream = Files.walk(dirPath)) {
                    stream.filter(Files::isRegularFile).sorted().forEach(file -> {
                        String name = file.getFileName().toString().toLowerCase();
                        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.')) : "";
                        if (IMAGE_EXTENSIONS.contains(ext)) {
                            try {
                                long size = Files.size(file);
                                Files.delete(file);
                                freedBytes.addAndGet(size);
                                deletedCount.incrementAndGet();
                            } catch (IOException e) {
                                log.error("删除文件失败: {}, 拒绝 ACK 触发重试", file, e);
                                throw new RuntimeException("删除文件失败: " + file, e);
                            }
                        }
                    });
                }
            }

            try {
                Files.deleteIfExists(dirPath);
            } catch (IOException e) {
                log.warn("删除空目录失败: {}", dirPath);
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
            try {
                channel.basicReject(tag, false);
            } catch (Exception ignored) {
            }
        }
    }
}
