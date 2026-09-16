package com.comicatlas.api.recovery.event;

import com.comicatlas.api.recovery.service.RecoveryBatchService;
import com.comicatlas.api.recovery.engine.RecoveryEngine;
import com.comicatlas.api.recovery.persistence.mapper.RecoveryTaskMapper;
import com.comicatlas.api.task.service.ManagementTaskService;
import org.springframework.data.redis.core.RedisTemplate;
import com.comicatlas.common.constant.MqQueues;
import com.comicatlas.common.event.ComicEvent;
import com.comicatlas.common.event.RecoveryFailedEvent;
import com.comicatlas.common.event.RecoveryScanCompletedEvent;
import com.comicatlas.common.mq.MqConsumerSupport;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/** 恢复结果消息适配器，仅负责事件分派和消费策略。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RecoveryEventHandler {
    private final RecoveryBatchService recoveryBatchService;
    private final MqConsumerSupport mqConsumerSupport;

    /** 兼容历史单元测试构造器，旧依赖组装为新的批次服务。 */
    public RecoveryEventHandler(RecoveryEngine recoveryEngine, RecoveryTaskMapper recoveryTaskMapper,
            RedisTemplate<String, Object> redisTemplate, ManagementTaskService managementTaskService,
            MqConsumerSupport mqConsumerSupport) {
        this(new RecoveryBatchService(recoveryEngine, recoveryTaskMapper, redisTemplate, managementTaskService),
                mqConsumerSupport);
    }

    @RabbitListener(queues = MqQueues.RECOVERY_RESULT)
    public void handle(ComicEvent event, Channel channel,
            @Header(AmqpHeaders.DELIVERY_TAG) long tag) {
        if (event instanceof RecoveryScanCompletedEvent scanEvent) {
            mqConsumerSupport.consume(channel, tag, "恢复扫描完成: taskId=" + scanEvent.taskId(),
                    () -> recoveryBatchService.processScanCompleted(scanEvent),
                    exception -> recoveryBatchService.markProcessingFailure(scanEvent.taskId(), exception),
                    MqConsumerSupport.FailurePolicy.REJECT_TO_DLQ);
        } else if (event instanceof RecoveryFailedEvent failedEvent) {
            mqConsumerSupport.consume(channel, tag, "恢复失败事件: taskId=" + failedEvent.taskId(),
                    () -> recoveryBatchService.processFailed(failedEvent));
        } else {
            log.warn("恢复结果队列收到未知事件类型: {}", event.getClass().getSimpleName());
            mqConsumerSupport.consume(channel, tag, "未知恢复事件", () -> { });
        }
    }
}
