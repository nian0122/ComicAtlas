package com.comicatlas.worker.recovery.event;

import com.comicatlas.common.constant.MqExchanges;
import com.comicatlas.common.constant.MqRoutingKeys;
import com.comicatlas.common.dto.ScanResultDTO;
import com.comicatlas.common.event.DirectoryScanCompletedEvent;
import com.comicatlas.common.event.DirectoryScanFailedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/** 目录扫描领域事件发布器。 */
@Component
@RequiredArgsConstructor
public class DirectoryScanEventPublisher {

    private final RabbitTemplate rabbitTemplate;

    public void publishCompleted(Long taskId, ScanResultDTO result) {
        rabbitTemplate.convertAndSend(MqExchanges.SCAN, MqRoutingKeys.SCAN_COMPLETED,
                new DirectoryScanCompletedEvent(UUID.randomUUID(), Instant.now(), taskId, result));
    }

    public void publishFailed(Long taskId, String message) {
        rabbitTemplate.convertAndSend(MqExchanges.SCAN, MqRoutingKeys.SCAN_FAILED,
                new DirectoryScanFailedEvent(UUID.randomUUID(), Instant.now(), taskId, message));
    }
}
