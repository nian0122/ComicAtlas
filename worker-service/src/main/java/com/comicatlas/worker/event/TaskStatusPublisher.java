package com.comicatlas.worker.event;

import com.comicatlas.common.event.ImportTaskCompletedEvent;
import com.comicatlas.common.event.TaskStatusChangedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class TaskStatusPublisher {
    private final RabbitTemplate rabbitTemplate;

    public void publishStatus(Long taskId, String newStatus, int progress, String downloadMethod, long speedBytesPerSec, int etaSeconds) {
        var event = new TaskStatusChangedEvent(
            UUID.randomUUID(), Instant.now(), taskId, newStatus, progress, downloadMethod, speedBytesPerSec, etaSeconds);
        rabbitTemplate.convertAndSend("comic.task", "status.changed", event);
    }

    public void publishImported(Long taskId, Long comicId) {
        var event = new ImportTaskCompletedEvent(UUID.randomUUID(), Instant.now(), taskId, comicId, null);
        rabbitTemplate.convertAndSend("comic.import", "task.completed", event);
    }
}
