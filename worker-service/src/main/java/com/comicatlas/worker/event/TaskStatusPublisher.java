package com.comicatlas.worker.event;

import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
@RequiredArgsConstructor
public class TaskStatusPublisher {
    private final RabbitTemplate rabbitTemplate;

    public void publishStatus(Long taskId, String newStatus, int progress, String downloadMethod, long speedBytesPerSec, int etaSeconds) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("messageId", UUID.randomUUID().toString());
        msg.put("taskId", taskId);
        msg.put("newStatus", newStatus);
        msg.put("progress", progress);
        if (downloadMethod != null) msg.put("downloadMethod", downloadMethod);
        msg.put("speedBytesPerSec", speedBytesPerSec);
        msg.put("etaSeconds", etaSeconds);
        rabbitTemplate.convertAndSend("comic.task", "status.changed", msg);
    }

    public void publishImported(Long taskId, Long comicId) {
        Map<String, Object> msg = Map.of(
            "messageId", UUID.randomUUID().toString(),
            "taskId", taskId,
            "comicId", comicId
        );
        rabbitTemplate.convertAndSend("comic.import", "task.completed", msg);
    }
}
