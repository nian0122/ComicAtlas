package com.comicatlas.api.importer.event;

import com.comicatlas.common.event.DirectoryScanRequestedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class DirectoryScanEventPublisher {

    private final RabbitTemplate rabbitTemplate;

    public void publishScanRequested(Long taskId, String directoryPath) {
        var event = new DirectoryScanRequestedEvent(
            UUID.randomUUID(), Instant.now(), taskId, directoryPath);
        rabbitTemplate.convertAndSend("comic.scan", "scan.requested", event);
    }
}
