package com.comicatlas.worker.event;

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

import java.util.*;

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
        long start = System.currentTimeMillis();
        log.info("LQ 生成: comicId={}, chapterId={}", comicId, chapterId);

        try {
            List<Integer> failedPages = optimizer.generateLq(comicId, chapterId, "1");
            rabbitTemplate.convertAndSend("comic.image", "lq.completed",
                Map.of("comicId", comicId, "chapterId", chapterId, "failedPages", failedPages));
            channel.basicAck(tag, false);
            log.info("LQ 完成: comicId={}, chapterId={}, failed={}, elapsed={}ms",
                comicId, chapterId, failedPages.size(), System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.error("LQ 失败: comicId={}, chapterId={}, elapsed={}ms",
                comicId, chapterId, System.currentTimeMillis() - start, e);
            try { channel.basicReject(tag, false); } catch (Exception ignored) {}
        }
    }
}
