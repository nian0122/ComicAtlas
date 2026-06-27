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

    public void publishImportTaskCreated(Long taskId, Long comicId, String sourceUrl, String sourceType, String sourcePath) {
        var msg = new java.util.LinkedHashMap<String, Object>();
        msg.put("messageId", UUID.randomUUID().toString());
        msg.put("taskId", taskId);
        msg.put("comicId", comicId);
        msg.put("sourceType", sourceType);
        if (sourceUrl != null) msg.put("sourceUrl", sourceUrl);
        if (sourcePath != null) msg.put("sourcePath", sourcePath);
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
