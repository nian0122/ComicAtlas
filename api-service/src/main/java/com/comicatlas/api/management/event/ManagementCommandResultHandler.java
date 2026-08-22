package com.comicatlas.api.management.event;

import com.comicatlas.api.management.dto.ManagementTaskItemResponse;
import com.comicatlas.api.management.service.ManagementTaskService;
import com.comicatlas.api.management.trash.TrashLifecycleCompletionService;
import com.comicatlas.api.outbox.service.InboxService;
import com.comicatlas.api.storage.service.ComicStatsService;
import com.comicatlas.api.media.service.MediaOperationCompletionService;
import com.comicatlas.api.metadata.service.MetadataRefreshCompletionService;
import com.comicatlas.api.metadata.service.MetadataUpdateCoordinator;
import com.comicatlas.api.upload.service.UploadCompletionService;
import com.comicatlas.common.constant.MqQueues;
import com.comicatlas.common.event.ComicEvent;
import com.comicatlas.common.event.ManagementCommandCompletedEvent;
import com.comicatlas.common.event.ManagementCommandFailedEvent;
import com.comicatlas.common.event.ManagementCommandProgressEvent;
import com.comicatlas.common.event.MediaUploadCompletedEvent;
import com.comicatlas.common.event.MetadataRefreshScanCompletedEvent;
import com.comicatlas.common.mq.MqConsumerSupport;
import com.comicatlas.contract.common.exception.BusinessException;
import com.comicatlas.api.management.enums.ManagementTaskStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;

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

    /** LQ 生成类操作（progress 状态转换路由用）。 */
    private static final Set<String> LQ_OPS = Set.of("LQ_GENERATE", "LQ_REGENERATE");

    /** 命令目标类型：漫画级（批量操作展开）。 */
    private static final String TARGET_TYPE_COMIC = "COMIC";

    /** 失败原因写入列上限（字符）：management_task_item.error_message 为 varchar(4096)。
     *  防御 Worker 或其他来源的超长 errorMessage 导致写库异常、结果事件进 DLQ、item 永久 QUEUED。 */
    private static final int MAX_ITEM_ERROR_MESSAGE_CHARS = 4000;

    private final ManagementTaskService managementTaskService;
    private final InboxService inboxService;
    private final MqConsumerSupport mqConsumerSupport;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;
    private final MediaOperationCompletionService mediaOperationCompletionService;
    private final TrashLifecycleCompletionService trashLifecycleCompletionService;
    private final UploadCompletionService uploadCompletionService;
    private final MetadataRefreshCompletionService metadataRefreshCompletionService;
    private final ComicStatsService comicStatsService;
    private final MetadataUpdateCoordinator metadataUpdateCoordinator;

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
                    () -> metadataRefreshCompletionService.handleCompleted(ev));
        } else {
            mqConsumerSupport.consume(channel, tag, "管理命令未知事件: " + raw.eventId(), () -> { });
        }
    }

    private void process(ComicEvent event, Long taskId, Long itemId, int attempt,
                         Channel channel, long tag, Runnable business) {
        String eventId = event.eventId().toString();
        mqConsumerSupport.consume(channel, tag, "管理命令结果: itemId=" + itemId, () -> {
            String payloadHash = sha256(toJson(event));
            try {
                transactionTemplate.executeWithoutResult(tx -> {
                    if (inboxService.isProcessed(eventId, payloadHash)) {
                        log.debug("Inbox 幂等跳过结果事件: eventId={}", eventId);
                        return;
                    }
                    business.run();
                    try {
                        inboxService.markProcessed(eventId, payloadHash, taskId, itemId, attempt);
                    } catch (DuplicateKeyException e) {
                        throw e;
                    }
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
        applyCompletedBusiness(ev);
    }

    private void applyCompletedBusiness(ManagementCommandCompletedEvent ev) {
        boolean comicScope = TARGET_TYPE_COMIC.equals(ev.targetType());
        switch (ev.operationType()) {
            case "LQ_GENERATE", "LQ_REGENERATE" -> {
                if (comicScope) {
                    for (Long chapterId : comicStatsService.chapterIdsOf(ev.targetId())) {
                        mediaOperationCompletionService.applyLqCompleted(chapterId, ev.lqSizes());
                    }
                } else {
                    mediaOperationCompletionService.applyLqCompleted(ev.targetId(), ev.lqSizes());
                }
            }
            case "HQ_DELETE" -> {
                if (comicScope) {
                    for (Long chapterId : comicStatsService.chapterIdsOf(ev.targetId())) {
                        mediaOperationCompletionService.applyHqDeleteCompleted(chapterId);
                    }
                } else {
                    mediaOperationCompletionService.applyHqDeleteCompleted(ev.targetId());
                }
            }
            case "TRANSCODE" -> {
                if (comicScope) {
                    for (Long mediaId : comicStatsService.mediaIdsOf(ev.targetId())) {
                        mediaOperationCompletionService.applyTranscodeCompleted(ev, mediaId);
                    }
                } else {
                    mediaOperationCompletionService.applyTranscodeCompleted(ev, ev.targetId());
                }
                mediaOperationCompletionService.maybeNotifyTranscodeTaskCompleted(ev);
            }
            case "COMIC_DELETE" -> trashLifecycleCompletionService.applyComicTrashCompleted(ev.targetId());
            case "CHAPTER_TRASH" -> trashLifecycleCompletionService.applyChapterTrashCompleted(ev.targetId());
            case "MEDIA_TRASH" -> trashLifecycleCompletionService.applyMediaTrashCompleted(ev);
            case "COMIC_RESTORE" -> trashLifecycleCompletionService.applyComicRestoreCompleted(ev.targetId());
            case "CHAPTER_RESTORE" -> trashLifecycleCompletionService.applyChapterRestoreCompleted(ev.targetId());
            case "MEDIA_RESTORE" -> trashLifecycleCompletionService.applyMediaRestoreCompleted(ev.targetId());
            case "COMIC_PURGE" -> trashLifecycleCompletionService.applyComicPurgeCompleted(ev.targetId());
            case "CHAPTER_PURGE" -> trashLifecycleCompletionService.applyChapterPurgeCompleted(ev.targetId());
            case "MEDIA_PURGE" -> trashLifecycleCompletionService.applyMediaPurgeCompleted(ev.targetId());
            case "METADATA_REFRESH" -> {
                // 元数据刷新完成由专用事件 MetadataRefreshScanCompletedEvent 走专用流程
                // （见 metadataRefreshCompletionService）；此处仅防御性兜底，避免通用 completed
                // 分支把 business.run() 整体包进事务导致快照读取进入事务。
                log.warn("收到通用 completed 事件携带 METADATA_REFRESH（应走专用完成事件）: comicId={}",
                        ev.targetId());
            }
            default -> log.warn("未知 completed 操作类型: {}", ev.operationType());
        }
        // 统一触发 metadata 同步：全部命令成功完成后按 comicId 合并重导出 metadata.json。
        // METADATA_REFRESH 已在专用流程触发，此处跳过避免重复；
        // 其余操作（LQ/HQ/转码/回收/恢复/上传等）完成后由 Coordinator 解析 comicId 并发
        // MetadataRefreshEvent（Worker 消费后原子写文件，API 不碰文件系统）。
        if (!"METADATA_REFRESH".equals(ev.operationType())) {
            metadataUpdateCoordinator.requestSyncForTarget(
                    ev.targetType(), ev.targetId(), ev.taskId(), "命令完成: " + ev.operationType());
        }
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
        applyFailedBusiness(ev);
    }

    private void applyFailedBusiness(ManagementCommandFailedEvent ev) {
        switch (ev.operationType()) {
            case "LQ_GENERATE", "LQ_REGENERATE" -> mediaOperationCompletionService.revertLqFailed(ev.targetId());
            case "HQ_DELETE" -> mediaOperationCompletionService.revertHqDeleteFailed(ev.targetId());
            case "TRANSCODE" -> mediaOperationCompletionService.revertTranscodeFailed(ev.targetId());
            case "MEDIA_UPLOAD", "MEDIA_REPLACE" -> uploadCompletionService.revertUploadFailed(ev.targetId());
            case "METADATA_REFRESH" -> metadataRefreshCompletionService.releaseComicRefreshing(ev.targetId());
            case "COMIC_DELETE", "CHAPTER_TRASH", "MEDIA_TRASH" ->
                    trashLifecycleCompletionService.applyTrashFailed(ev.targetType(), ev.targetId(), ev.taskId());
            case "COMIC_RESTORE", "CHAPTER_RESTORE", "MEDIA_RESTORE" ->
                    trashLifecycleCompletionService.revertToTrashed(ev.targetType(), ev.targetId());
            case "COMIC_PURGE", "CHAPTER_PURGE", "MEDIA_PURGE" ->
                    trashLifecycleCompletionService.revertToTrashed(ev.targetType(), ev.targetId());
            default -> { }
        }
        log.info("命令失败业务回退: itemId={}, op={}, target={}", ev.itemId(), ev.operationType(), ev.targetId());
    }

    // ======================== Progress ========================

    private void handleProgress(ManagementCommandProgressEvent ev) {
        boolean applied = managementTaskService.updateItemProgress(
                ev.itemId(), ev.attempt(), ev.progress(), ev.stage());
        if (!applied) {
            return;
        }
        applyProgressTransition(ev);
    }

    private void applyProgressTransition(ManagementCommandProgressEvent ev) {
        if (LQ_OPS.contains(ev.operationType())) {
            mediaOperationCompletionService.transitionLqGenerating(ev.targetId());
        } else if ("HQ_DELETE".equals(ev.operationType())) {
            mediaOperationCompletionService.transitionHqDeleting(ev.targetId());
        } else if ("TRANSCODE".equals(ev.operationType())) {
            mediaOperationCompletionService.transitionTranscoding(ev.targetId());
        }
    }

    // ======================== 媒体上传/替换 Completed ========================

    private void handleUploadCompleted(MediaUploadCompletedEvent ev) {
        ManagementTaskItemResponse item = managementTaskService.updateItemStatus(
                ev.itemId(), ManagementTaskStatus.SUCCEEDED, null, null, null, ev.attempt());
        if (item.getStatus() != ManagementTaskStatus.SUCCEEDED) {
            log.info("upload completed 未生效（旧 attempt/已终态）: itemId={}", ev.itemId());
            return;
        }
        uploadCompletionService.applyUploadCompletedBusiness(ev);
        // 媒体上传/替换同样改变文件与 media 状态，统一触发 metadata 同步
        metadataUpdateCoordinator.requestSyncForTarget(
                ev.targetType(), ev.targetId(), ev.taskId(), "上传完成: " + ev.operationType());
    }

    // ======================== 辅助 ========================

    private static String truncateItemErrorMessage(String errorMessage) {
        if (errorMessage == null || errorMessage.length() <= MAX_ITEM_ERROR_MESSAGE_CHARS) {
            return errorMessage;
        }
        return errorMessage.substring(0, MAX_ITEM_ERROR_MESSAGE_CHARS) + "...（已截断）";
    }

    private String toJson(ComicEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new BusinessException("结果事件序列化失败: " + event.eventId(), e);
        }
    }

    private static String sha256(String input) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new BusinessException("计算结果事件摘要失败", e);
        }
    }
}
