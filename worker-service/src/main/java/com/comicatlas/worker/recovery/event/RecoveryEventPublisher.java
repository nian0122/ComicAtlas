package com.comicatlas.worker.recovery.event;

import com.comicatlas.common.constant.MqExchanges;
import com.comicatlas.common.constant.MqRoutingKeys;
import com.comicatlas.common.event.RecoveryFailedEvent;
import com.comicatlas.common.event.RecoveryScanCompletedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 存储恢复领域事件发布器。 */
@Component
@RequiredArgsConstructor
public class RecoveryEventPublisher {

    private final RabbitTemplate rabbitTemplate;

    public void publishScanCompleted(Long taskId, List<Long> comicIds) {
        rabbitTemplate.convertAndSend(MqExchanges.RECOVERY, MqRoutingKeys.RECOVERY_PROGRESS,
                new RecoveryScanCompletedEvent(UUID.randomUUID(), Instant.now(), taskId, comicIds));
    }

    public void publishFailed(Long taskId, String message) {
        rabbitTemplate.convertAndSend(MqExchanges.RECOVERY, MqRoutingKeys.RECOVERY_FAILED,
                new RecoveryFailedEvent(UUID.randomUUID(), Instant.now(), taskId, message));
    }
}
