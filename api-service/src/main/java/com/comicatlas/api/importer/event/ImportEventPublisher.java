package com.comicatlas.api.importer.event;

import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class ImportEventPublisher {

    private final RabbitTemplate rabbitTemplate;

    public void publishImportTaskCreated(Long taskId, Long comicId, String sourceUrl, String sourceType) {
        Map<String, Object> msg = Map.of(
            "messageId", UUID.randomUUID().toString(),
            "taskId", taskId,
            "comicId", comicId,
            "sourceUrl", sourceUrl,
            "sourceType", sourceType
        );
        rabbitTemplate.convertAndSend("comic.import", "task.created", msg);
    }

    public void publishDeleteRequested(Long comicId) {
        Map<String, Object> msg = Map.of(
            "messageId", UUID.randomUUID().toString(),
            "comicId", comicId
        );
        rabbitTemplate.convertAndSend("comic.delete", "delete.requested", msg);
    }
}
