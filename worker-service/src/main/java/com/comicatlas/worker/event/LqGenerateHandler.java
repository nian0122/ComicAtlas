package com.comicatlas.worker.event;

import com.comicatlas.common.event.LqCompletedEvent;
import com.comicatlas.common.event.LqGenerateEvent;
import com.comicatlas.worker.entity.ExportMedia;
import com.comicatlas.worker.file.storage.StorageProperties;
import com.comicatlas.worker.file.storage.StorageRoot;
import com.comicatlas.worker.image.ImageOptimizer;
import com.comicatlas.worker.mapper.ExportMediaMapper;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * LQ 生成任务处理器。
 * 从 DB 读取页面真实 root+path，不使用 globalOrder 拼目录。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LqGenerateHandler {
    private final ImageOptimizer optimizer;
    private final RabbitTemplate rabbitTemplate;
    private final ExportMediaMapper mediaMapper;
    private final StorageProperties storageProperties;

    @RabbitListener(queues = "lq.generate.queue")
    public void handle(LqGenerateEvent event,
            Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long tag) {
        Long comicId = event.comicId();
        Long chapterId = event.chapterId();

        long start = System.currentTimeMillis();
        log.info("LQ 生成开始: comicId={}, chapterId={}", comicId, chapterId);

        try {
            // 从 DB 读取章节页面，获取真实 hqPath
            List<ExportMedia> pages = mediaMapper.selectByChapterId(chapterId);
            if (pages.isEmpty()) {
                log.info("章节无页面，跳过 LQ: chapterId={}", chapterId);
                channel.basicAck(tag, false);
                return;
            }

            // 从第一条 hqPath 提取 HQ 目录
            StorageRoot hqRoot = storageProperties.getRoots().get("HQ");
            StorageRoot lqRoot = storageProperties.getRoots().get("LQ");
            if (hqRoot == null || lqRoot == null) {
                log.error("HQ/LQ 存储根未配置");
                channel.basicReject(tag, false);
                return;
            }

            String firstHqPath = pages.get(0).getHqPath();
            String relativeDir = extractDirectory(firstHqPath);
            Path hqDir = hqRoot.resolve(relativeDir);
            Path lqDir = lqRoot.resolve(relativeDir);

            ImageOptimizer.RunResult result = optimizer.generateLq(
                    comicId, chapterId, hqDir, lqDir);

            List<Integer> failedPages = result.getPages().stream()
                    .filter(p -> "failed".equals(p.getStatus()))
                    .map(p -> p.getPageNumber().intValue())
                    .toList();

            LqCompletedEvent completedEvent = new LqCompletedEvent(
                    UUID.randomUUID(), Instant.now(),
                    comicId, chapterId, failedPages,
                    result.getProcessed(), result.getSkipped(), result.getElapsedMs());
            rabbitTemplate.convertAndSend("comic.image", "lq.completed", completedEvent);
            channel.basicAck(tag, false);
            log.info("LQ 生成完成: comicId={}, chapterId={}, failed={}, elapsed={}ms",
                    comicId, chapterId, failedPages.size(), System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.error("LQ 生成失败: comicId={}, chapterId={}, elapsed={}ms",
                    comicId, chapterId, System.currentTimeMillis() - start, e);
            try { channel.basicReject(tag, false); } catch (Exception ex) { log.warn("消息 reject 失败: tag={}", tag, ex); }
        }
    }

    private static String extractDirectory(String hqPath) {
        if (hqPath == null) { return ""; }
        int lastSlash = hqPath.lastIndexOf('/');
        return lastSlash > 0 ? hqPath.substring(0, lastSlash) : hqPath;
    }
}
