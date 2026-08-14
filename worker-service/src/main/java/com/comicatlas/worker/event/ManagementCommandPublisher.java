package com.comicatlas.worker.event;

import com.comicatlas.common.constant.MqExchanges;
import com.comicatlas.common.constant.MqRoutingKeys;
import com.comicatlas.common.event.ManagementCommandCompletedEvent;
import com.comicatlas.common.event.ManagementCommandFailedEvent;
import com.comicatlas.common.event.ManagementCommandProgressEvent;
import com.comicatlas.common.event.ManagementCommandRequestedEvent;
import com.comicatlas.common.event.MediaUploadCompletedEvent;
import com.comicatlas.common.event.MediaUploadCompletedEvent.MediaAnalysisResult;
import com.comicatlas.common.event.MetadataRefreshScanCompletedEvent;
import com.comicatlas.common.event.payload.TranscodeMediaInfo;
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

    /**
     * FAILED 事件 errorMessage 保留上限（字符）。API 端写入 management_task_item.error_message
     * （varchar(4096)），超长消息（如内嵌外部进程 stdout 的异常文本可达数十 KB）会导致
     * API 消费端写库异常、结果事件进 DLQ，item 永远停在 QUEUED。截断后保留头部，
     * 完整错误由 Worker 日志承载。
     */
    private static final int MAX_ERROR_MESSAGE_CHARS = 2000;

    private static String truncateErrorMessage(String errorMessage) {
        if (errorMessage == null || errorMessage.length() <= MAX_ERROR_MESSAGE_CHARS) {
            return errorMessage;
        }
        return errorMessage.substring(0, MAX_ERROR_MESSAGE_CHARS) + "...（已截断，完整信息见 Worker 日志）";
    }

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
                        truncateErrorMessage(errorMessage)));
    }

    /**
     * 元数据扫盘刷新完成事件（Worker → API）。
     * <p>
     * 复用 MANAGEMENT exchange + COMMAND_COMPLETED routing（不新增队列）：
     * 快照产物已落盘，事件只携带引用路径 + SHA-256 校验 + 字节数 + schema 版本，
     * 供 API 端按引用读取并校验完整性后与数据库比对刷新元数据。
     */
    public void metadataRefreshScanCompleted(ManagementCommandRequestedEvent cmd,
                                             String snapshotRef, String snapshotSha256,
                                             long snapshotBytes, int schemaVersion) {
        rabbitTemplate.convertAndSend(EXCHANGE, MqRoutingKeys.COMMAND_COMPLETED,
                new MetadataRefreshScanCompletedEvent(UUID.randomUUID(), Instant.now(), 1,
                        cmd.taskId(), cmd.itemId(), cmd.attempt(),
                        cmd.operationType(), cmd.targetType(), cmd.targetId(),
                        snapshotRef, snapshotSha256, snapshotBytes, schemaVersion));
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
