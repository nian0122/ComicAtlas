package com.comicatlas.worker.event;

import com.comicatlas.common.constant.MqExchanges;
import com.comicatlas.common.constant.MqRoutingKeys;
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

    public void publishStatus(Long taskId, String newStatus, int progress, String downloadMethod,
                              long speedBytesPerSec, int etaSeconds, String errorMessage) {
        var event = new TaskStatusChangedEvent(
            UUID.randomUUID(), Instant.now(), taskId, newStatus, progress, downloadMethod,
            speedBytesPerSec, etaSeconds, errorMessage);
        rabbitTemplate.convertAndSend(MqExchanges.TASK, MqRoutingKeys.STATUS_CHANGED, event);
    }

    public void publishImported(Long taskId, Long comicId) {
        var event = new ImportTaskCompletedEvent(UUID.randomUUID(), Instant.now(), taskId, comicId, null);
        rabbitTemplate.convertAndSend(MqExchanges.IMPORT, MqRoutingKeys.TASK_COMPLETED, event);
    }
}
