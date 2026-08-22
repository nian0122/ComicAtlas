package com.comicatlas.worker.importer.event;

import com.comicatlas.common.constant.MqExchanges;
import com.comicatlas.common.constant.MqRoutingKeys;
import com.comicatlas.common.event.ImportStorageFinalizeCompletedEvent;
import com.comicatlas.common.event.ImportStorageFinalizeFailedEvent;
import com.comicatlas.common.event.ImportStorageFinalizeRequestedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/** 导入存储最终化领域事件发布器。 */
@Component
@RequiredArgsConstructor
public class ImportStorageFinalizeEventPublisher {

    private final RabbitTemplate rabbitTemplate;

    public void publishCompleted(ImportStorageFinalizeRequestedEvent event, int mediaCount) {
        rabbitTemplate.convertAndSend(MqExchanges.IMPORT, MqRoutingKeys.IMPORT_STORAGE_FINALIZE_COMPLETED,
                new ImportStorageFinalizeCompletedEvent(UUID.randomUUID(), Instant.now(),
                        event.taskId(), event.comicId(), event.globalOrder(), event.chapterId(),
                        event.targetDir(), mediaCount));
    }

    public void publishFailed(ImportStorageFinalizeRequestedEvent event, String errorCode, String message) {
        rabbitTemplate.convertAndSend(MqExchanges.IMPORT, MqRoutingKeys.IMPORT_STORAGE_FINALIZE_FAILED,
                new ImportStorageFinalizeFailedEvent(UUID.randomUUID(), Instant.now(),
                        event.taskId(), event.comicId(), event.globalOrder(), event.chapterId(),
                        errorCode, message));
    }
}
