package com.comicatlas.api.export.event;

import com.comicatlas.common.event.ExportTaskCreatedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class ExportEventPublisher {

    private final RabbitTemplate rabbitTemplate;

    public void publishExportTaskCreated(Long taskId, Long comicId) {
        var event = new ExportTaskCreatedEvent(
            UUID.randomUUID(), Instant.now(), taskId, comicId);
        rabbitTemplate.convertAndSend("comic.export", "task.created", event);
    }
}
