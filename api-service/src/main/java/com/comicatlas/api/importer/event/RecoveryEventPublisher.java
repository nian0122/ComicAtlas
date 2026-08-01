package com.comicatlas.api.importer.event;

import com.comicatlas.common.event.RecoveryCompletedEvent;
import com.comicatlas.common.event.RecoveryFailedEvent;
import com.comicatlas.common.event.RecoveryProgressEvent;
import com.comicatlas.common.event.RecoveryRequestedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class RecoveryEventPublisher {

    private final RabbitTemplate rabbitTemplate;

    public void publishRecoveryRequested(Long taskId) {
        var event = new RecoveryRequestedEvent(
            UUID.randomUUID(), Instant.now(), taskId);
        rabbitTemplate.convertAndSend("comic.recovery", "recovery.requested", event);
    }

    public void publishRecoveryProgress(Long taskId, int totalComics, int recoveredComics,
                                         int skippedComics, int placeholderComics, int errorComics) {
        var event = new RecoveryProgressEvent(
            UUID.randomUUID(), Instant.now(), taskId,
            totalComics, recoveredComics, skippedComics, placeholderComics, errorComics);
        rabbitTemplate.convertAndSend("comic.recovery", "recovery.progress", event);
    }

    public void publishRecoveryCompleted(Long taskId, int totalComics, int recoveredComics,
                                          int skippedComics, int placeholderComics, int errorComics) {
        var event = new RecoveryCompletedEvent(
            UUID.randomUUID(), Instant.now(), taskId,
            totalComics, recoveredComics, skippedComics, placeholderComics, errorComics);
        rabbitTemplate.convertAndSend("comic.recovery", "recovery.completed", event);
    }

    public void publishRecoveryFailed(Long taskId, String errorMessage) {
        var event = new RecoveryFailedEvent(
            UUID.randomUUID(), Instant.now(), taskId, errorMessage);
        rabbitTemplate.convertAndSend("comic.recovery", "recovery.failed", event);
    }
}
