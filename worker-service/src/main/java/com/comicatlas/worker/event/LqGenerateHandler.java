package com.comicatlas.worker.event;

import com.comicatlas.worker.image.ImageOptimizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class LqGenerateHandler {
    private final ImageOptimizer optimizer;
    private final RabbitTemplate rabbitTemplate;

    @RabbitListener(queues = "lq.generate.queue")
    public void handle(Map<String, Object> msg) {
        Long comicId = Long.valueOf(msg.get("comicId").toString());
        Long chapterId = Long.valueOf(msg.get("chapterId").toString());
        log.info("LQ generation: comicId={}, chapterId={}", comicId, chapterId);

        try {
            List<Integer> failedPages = optimizer.generateLq(comicId, chapterId, "1");
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("messageId", UUID.randomUUID().toString());
            result.put("comicId", comicId);
            result.put("chapterId", chapterId);
            result.put("totalPages", 0);
            result.put("failedPages", failedPages);
            rabbitTemplate.convertAndSend("comic.image", "lq.completed", result);
        } catch (Exception e) {
            log.error("LQ generation failed: comicId={}", comicId, e);
        }
    }
}
