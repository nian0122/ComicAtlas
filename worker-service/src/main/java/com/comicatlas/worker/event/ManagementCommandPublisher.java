package com.comicatlas.worker.event;

import com.comicatlas.common.constant.MqExchanges;
import com.comicatlas.common.constant.MqRoutingKeys;
import com.comicatlas.common.event.ManagementCommandCompletedEvent;
import com.comicatlas.common.event.ManagementCommandFailedEvent;
import com.comicatlas.common.event.ManagementCommandProgressEvent;
import com.comicatlas.common.event.ManagementCommandRequestedEvent;
import com.comicatlas.common.event.MediaUploadCompletedEvent;
import com.comicatlas.common.event.MediaUploadCompletedEvent.MediaAnalysisResult;
import com.comicatlas.common.event.TranscodeMediaInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 管理命令结果发布器（Worker → API）。
 * <p>
 * 回传 progress/completed/failed 事件，统一携带 taskId/itemId/attempt，
 * API 端据此做 attempt 条件更新。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ManagementCommandPublisher {

    private final RabbitTemplate rabbitTemplate;

    private static final String EXCHANGE = MqExchanges.MANAGEMENT;

    public void progress(ManagementCommandRequestedEvent cmd, int progress, String stage) {
        rabbitTemplate.convertAndSend(EXCHANGE, MqRoutingKeys.COMMAND_PROGRESS,
                new ManagementCommandProgressEvent(UUID.randomUUID(), Instant.now(), 1,
                        cmd.taskId(), cmd.itemId(), cmd.attempt(),
                        cmd.operationType(), cmd.targetType(), cmd.targetId(),
                        progress, stage));
    }

    public void completed(ManagementCommandRequestedEvent cmd) {
        completed(cmd, null);
    }

    public void completed(ManagementCommandRequestedEvent cmd, TranscodeMediaInfo transcode) {
        rabbitTemplate.convertAndSend(EXCHANGE, MqRoutingKeys.COMMAND_COMPLETED,
                new ManagementCommandCompletedEvent(UUID.randomUUID(), Instant.now(), 1,
                        cmd.taskId(), cmd.itemId(), cmd.attempt(),
                        cmd.operationType(), cmd.targetType(), cmd.targetId(),
                        transcode));
    }

    public void failed(ManagementCommandRequestedEvent cmd, String errorMessage) {
        rabbitTemplate.convertAndSend(EXCHANGE, MqRoutingKeys.COMMAND_FAILED,
                new ManagementCommandFailedEvent(UUID.randomUUID(), Instant.now(), 1,
                        cmd.taskId(), cmd.itemId(), cmd.attempt(),
                        cmd.operationType(), cmd.targetType(), cmd.targetId(),
                        errorMessage));
    }

    public void uploadCompleted(ManagementCommandRequestedEvent cmd,
                                List<MediaAnalysisResult> results) {
        rabbitTemplate.convertAndSend(EXCHANGE, MqRoutingKeys.COMMAND_COMPLETED,
                new MediaUploadCompletedEvent(UUID.randomUUID(), Instant.now(), 1,
                        cmd.taskId(), cmd.itemId(), cmd.attempt(),
                        cmd.operationType(), cmd.targetType(), cmd.targetId(),
                        results));
    }
}
