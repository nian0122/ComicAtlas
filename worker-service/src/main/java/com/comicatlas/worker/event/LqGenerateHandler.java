package com.comicatlas.worker.event;

import com.comicatlas.common.event.LqCompletedEvent;
import com.comicatlas.common.event.LqGenerateEvent;
import com.comicatlas.worker.image.ImageOptimizer;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * LQ 生成任务处理器。
 * 调用外部 Go 工具进行并发 WebP 压缩，完成后发送 lq.completed 事件回 API。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LqGenerateHandler {
    private final ImageOptimizer optimizer;
    private final RabbitTemplate rabbitTemplate;

    @RabbitListener(queues = "lq.generate.queue")
    public void handle(LqGenerateEvent event,
            Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long tag) {
        Long comicId = event.comicId();
        Long chapterId = event.chapterId();
        String chapterNo = event.chapterNo();

        // chapterNo 为 null 表示旧版事件未携带，拒绝并记录
        if (chapterNo == null || chapterNo.isBlank()) {
            log.error("chapterNo 为空，无法生成 LQ: comicId={}, chapterId={}", comicId, chapterId);
            try {
                channel.basicReject(tag, false);
            } catch (Exception ignored) {
            }
            return;
        }

        long start = System.currentTimeMillis();
        log.info("LQ 生成开始: comicId={}, chapterId={}, chapterNo={}", comicId, chapterId, chapterNo);

        try {
            ImageOptimizer.RunResult result = optimizer.generateLq(comicId, chapterId, chapterNo);

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
            log.error("LQ 生成失败: comicId={}, chapterId={}, chapterNo={}, elapsed={}ms",
                    comicId, chapterId, chapterNo, System.currentTimeMillis() - start, e);
            try {
                channel.basicReject(tag, false);
            } catch (Exception ignored) {
            }
        }
    }
}
