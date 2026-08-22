package com.comicatlas.worker.exporter.publisher;

import com.comicatlas.common.constant.MqExchanges;
import com.comicatlas.common.constant.MqRoutingKeys;
import com.comicatlas.common.event.ExportTaskCompletedEvent;
import com.comicatlas.common.event.ExportTaskFailedEvent;
import com.comicatlas.common.event.ExportTaskStartedEvent;
import com.comicatlas.worker.exporter.service.ExportService;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/** 导出任务领域事件发布器。 */
@Component
@RequiredArgsConstructor
public class ExportEventPublisher {

    private final RabbitTemplate rabbitTemplate;

    public void publishStarted(Long taskId, Long comicId) {
        rabbitTemplate.convertAndSend(MqExchanges.EXPORT, MqRoutingKeys.TASK_STARTED,
                new ExportTaskStartedEvent(UUID.randomUUID(), Instant.now(), taskId, comicId));
    }

    public void publishCompleted(Long taskId, Long comicId, ExportService.ExportOutput output) {
        rabbitTemplate.convertAndSend(MqExchanges.EXPORT, MqRoutingKeys.TASK_COMPLETED,
                new ExportTaskCompletedEvent(UUID.randomUUID(), Instant.now(), taskId, comicId,
                        "EXPORT", output.fileName(), output.size()));
    }

    public void publishFailed(Long taskId, Long comicId, String errorCode, String message) {
        rabbitTemplate.convertAndSend(MqExchanges.EXPORT, MqRoutingKeys.TASK_FAILED,
                new ExportTaskFailedEvent(UUID.randomUUID(), Instant.now(), taskId, comicId,
                        errorCode, message));
    }
}
