package com.comicatlas.api.importer.event;

import com.comicatlas.common.event.DeleteRequestedEvent;
import com.comicatlas.common.event.ImportTaskCreatedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class ImportEventPublisher {

    private final RabbitTemplate rabbitTemplate;

    public void publishImportTaskCreated(Long taskId, Long comicId, String sourceType, String sourcePath) {
        var event = new ImportTaskCreatedEvent(
            UUID.randomUUID(), Instant.now(), taskId, comicId, sourceType, sourcePath);
        rabbitTemplate.convertAndSend("comic.import", "task.created", event);
    }

    public void publishDeleteRequested(Long comicId) {
        var event = new DeleteRequestedEvent(UUID.randomUUID(), Instant.now(), comicId);
        rabbitTemplate.convertAndSend("comic.delete", "delete.requested", event);
    }
}
