package com.comicatlas.api.task.event;

import com.comicatlas.api.task.dto.ManagementTaskItemResponse;
import com.comicatlas.api.task.service.ManagementTaskService;
import com.comicatlas.api.outbox.service.InboxService;
import com.comicatlas.api.outbox.service.EventFingerprintService;
import com.comicatlas.common.constant.MqQueues;
import com.comicatlas.common.event.ComicEvent;
import com.comicatlas.common.event.ManagementCommandCompletedEvent;
import com.comicatlas.common.event.ManagementCommandFailedEvent;
import com.comicatlas.common.event.ManagementCommandProgressEvent;
import com.comicatlas.common.event.MediaUploadCompletedEvent;
import com.comicatlas.common.event.MetadataRefreshScanCompletedEvent;
import com.comicatlas.common.mq.MqConsumerSupport;
import com.comicatlas.api.task.enums.ManagementTaskStatus;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;


/**
 * 管理命令结果事件处理器（Worker → API）——纯状态机 + 路由分发。
 * <p>
 * 职责边界：消费 {@link MqQueues#MANAGEMENT_RESULT} 的 completed/failed/progress 事件，
 * 通过 Inbox（eventId + payloadHash）保证恰好一次，attempt 条件更新保证
 * 重复/乱序/旧 attempt 结果对业务只生效一次；具体业务落库按操作类型路由到
 * 各领域 completion service（存储操作 / 回收生命周期 / 上传 / 元数据刷新）。
 * Worker 不写 DB，业务状态由 API 依据结果事件更新。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ManagementCommandResultHandler {

    /** 失败原因写入列上限（字符）：management_task_item.error_message 为 varchar(4096)。
     *  防御 Worker 或其他来源的超长 errorMessage 导致写库异常、结果事件进 DLQ、item 永久 QUEUED。 */
    private static final int MAX_ITEM_ERROR_MESSAGE_CHARS = 4000;

    private final ManagementTaskService managementTaskService;
    private final InboxService inboxService;
    private final MqConsumerSupport mqConsumerSupport;
    private final TransactionTemplate transactionTemplate;
    private final EventFingerprintService eventFingerprintService;
    private final ManagementResultRouter managementResultRouter;

    @RabbitListener(queues = MqQueues.MANAGEMENT_RESULT)
    public void handleResult(ComicEvent raw,
            Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long tag) {
        if (raw instanceof ManagementCommandCompletedEvent ev) {
            process(ev, ev.taskId(), ev.itemId(), ev.attempt(), channel, tag, () -> handleCompleted(ev));
        } else if (raw instanceof ManagementCommandFailedEvent ev) {
            process(ev, ev.taskId(), ev.itemId(), ev.attempt(), channel, tag, () -> handleFailed(ev));
        } else if (raw instanceof ManagementCommandProgressEvent ev) {
            process(ev, ev.taskId(), ev.itemId(), ev.attempt(), channel, tag, () -> handleProgress(ev));
        } else if (raw instanceof MediaUploadCompletedEvent ev) {
            process(ev, ev.taskId(), ev.itemId(), ev.attempt(), channel, tag, () -> handleUploadCompleted(ev));
        } else if (raw instanceof MetadataRefreshScanCompletedEvent ev) {
            // 元数据刷新专用流程：快照读取/校验必须在事务外，不走通用 process 事务分支
            mqConsumerSupport.consume(channel, tag, "元数据刷新完成: itemId=" + ev.itemId(),
                    () -> managementResultRouter.routeMetadataRefreshCompleted(ev));
        } else {
            mqConsumerSupport.consume(channel, tag, "管理命令未知事件: " + raw.eventId(), () -> { });
        }
    }

    private void process(ComicEvent event, Long taskId, Long itemId, int attempt,
                         Channel channel, long tag, Runnable business) {
        String eventId = event.eventId().toString();
        mqConsumerSupport.consume(channel, tag, "管理命令结果: itemId=" + itemId, () -> {
            String payloadHash = eventFingerprintService.fingerprint(event);
            try {
                transactionTemplate.executeWithoutResult(tx -> {
                    if (inboxService.isProcessed(eventId, payloadHash)) {
                        log.debug("Inbox 幂等跳过结果事件: eventId={}", eventId);
                        return;
                    }
                    business.run();
                    inboxService.markProcessed(eventId, payloadHash, taskId, itemId, attempt);
                });
            } catch (DuplicateKeyException e) {
                // Inbox 并发重复结果事件：已由其他投递处理，视为成功 ack（保留原 catch 语义）
                log.warn("Inbox 并发重复结果事件，已由其他投递处理: eventId={}", eventId);
            }
        });
    }

    // ======================== Completed ========================

    private void handleCompleted(ManagementCommandCompletedEvent ev) {
        ManagementTaskItemResponse item = managementTaskService.updateItemStatus(
                ev.itemId(), ManagementTaskStatus.SUCCEEDED, null, null, null, ev.attempt());
        if (item.getStatus() != ManagementTaskStatus.SUCCEEDED) {
            log.info("completed 结果未生效（旧 attempt/已终态）: itemId={}, attempt={}", ev.itemId(), ev.attempt());
            return;
        }
        managementResultRouter.routeCompleted(ev);
    }

    // ======================== Failed ========================

    private void handleFailed(ManagementCommandFailedEvent ev) {
        ManagementTaskItemResponse item = managementTaskService.updateItemStatus(
                ev.itemId(), ManagementTaskStatus.FAILED,
                truncateItemErrorMessage(ev.errorMessage()), null, null, ev.attempt());
        if (item.getStatus() != ManagementTaskStatus.FAILED) {
            log.info("failed 结果未生效（旧 attempt/已终态）: itemId={}, attempt={}", ev.itemId(), ev.attempt());
            return;
        }
        managementResultRouter.routeFailed(ev);
    }

    // ======================== Progress ========================

    private void handleProgress(ManagementCommandProgressEvent ev) {
        boolean applied = managementTaskService.updateItemProgress(
                ev.itemId(), ev.attempt(), ev.progress(), ev.stage());
        if (!applied) {
            return;
        }
        managementResultRouter.routeProgress(ev);
    }

    // ======================== 媒体上传/替换 Completed ========================

    private void handleUploadCompleted(MediaUploadCompletedEvent ev) {
        ManagementTaskItemResponse item = managementTaskService.updateItemStatus(
                ev.itemId(), ManagementTaskStatus.SUCCEEDED, null, null, null, ev.attempt());
        if (item.getStatus() != ManagementTaskStatus.SUCCEEDED) {
            log.info("upload completed 未生效（旧 attempt/已终态）: itemId={}", ev.itemId());
            return;
        }
        managementResultRouter.routeUploadCompleted(ev);
    }

    // ======================== 辅助 ========================

    private static String truncateItemErrorMessage(String errorMessage) {
        if (errorMessage == null || errorMessage.length() <= MAX_ITEM_ERROR_MESSAGE_CHARS) {
            return errorMessage;
        }
        return errorMessage.substring(0, MAX_ITEM_ERROR_MESSAGE_CHARS) + "...（已截断）";
    }

}
