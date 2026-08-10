package com.comicatlas.api.management.event;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.comicatlas.api.comic.entity.Chapter;
import com.comicatlas.api.comic.entity.Comic;
import com.comicatlas.api.comic.entity.Media;
import com.comicatlas.api.comic.mapper.CatalogMapper;
import com.comicatlas.api.comic.mapper.ChapterMapper;
import com.comicatlas.api.comic.mapper.ComicMapper;
import com.comicatlas.api.comic.mapper.ComicTagMapper;
import com.comicatlas.api.comic.mapper.MediaMapper;
import com.comicatlas.api.common.enums.ComicStatus;
import com.comicatlas.api.common.enums.HqStatus;
import com.comicatlas.api.common.enums.LqStatus;
import com.comicatlas.api.common.enums.ManagementTaskStatus;
import com.comicatlas.api.common.enums.MediaLifecycleStatus;
import com.comicatlas.api.common.enums.TaskType;
import com.comicatlas.api.common.enums.TranscodeStatus;
import com.comicatlas.api.common.exception.BusinessException;
import com.comicatlas.api.common.exception.SnapshotUnavailableException;
import com.comicatlas.api.common.storage.ApiStorageProperties;
import com.comicatlas.api.common.storage.PathTraversalException;
import com.comicatlas.api.management.dto.ManagementTaskItemResponse;
import com.comicatlas.api.management.entity.ManagementTaskItem;
import com.comicatlas.api.management.mapper.ManagementTaskItemMapper;
import com.comicatlas.api.management.service.ManagementTaskService;
import com.comicatlas.api.management.trash.TrashManifestService;
import com.comicatlas.api.outbox.service.InboxService;
import com.comicatlas.api.outbox.service.OutboxService;
import com.comicatlas.api.reader.entity.ReadingHistory;
import com.comicatlas.api.reader.mapper.ReadingHistoryMapper;
import com.comicatlas.api.storage.service.MediaMetadataSyncService;
import com.comicatlas.api.storage.service.MetadataRefreshService;
import com.comicatlas.api.storage.service.MetadataRefreshService.MetadataRefreshLoadRequest;
import com.comicatlas.common.constant.MqExchanges;
import com.comicatlas.common.constant.MqQueues;
import com.comicatlas.common.constant.MqRoutingKeys;
import com.comicatlas.common.dto.MetadataRefreshSnapshotDTO;
import com.comicatlas.common.dto.TrashManifestItemDTO;
import com.comicatlas.api.common.enums.ChapterLifecycleStatus;
import com.comicatlas.common.event.ComicEvent;
import com.comicatlas.common.event.ManagementCommandCompletedEvent;
import com.comicatlas.common.event.ManagementCommandFailedEvent;
import com.comicatlas.common.event.ManagementCommandProgressEvent;
import com.comicatlas.common.event.MediaUploadCompletedEvent;
import com.comicatlas.common.event.MediaUploadCompletedEvent.MediaAnalysisResult;
import com.comicatlas.common.event.MetadataRefreshEvent;
import com.comicatlas.common.event.MetadataRefreshScanCompletedEvent;
import com.comicatlas.common.event.payload.LqGenerationResult;
import com.comicatlas.common.event.payload.LqMediaResult;
import com.comicatlas.common.event.payload.TranscodeMediaInfo;
import com.comicatlas.common.mq.MqConsumerSupport;
import com.comicatlas.api.upload.UploadSessionService;
import com.comicatlas.api.upload.UploadSessionStatus;
import com.comicatlas.api.upload.entity.UploadSession;
import com.comicatlas.api.upload.mapper.UploadSessionMapper;
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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 管理命令结果事件处理器（Worker → API）。
 * <p>
 * 消费 {@link MqExchanges#MANAGEMENT} 的 completed/failed/progress 事件，
 * 通过 Inbox（eventId + payloadHash）保证恰好一次，attempt 条件更新保证
 * 重复/乱序/旧 attempt 结果对业务只生效一次。Worker 不写 DB，业务状态由
 * API 依据结果事件更新。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ManagementCommandResultHandler {

    private static final Set<String> LQ_OPS = Set.of("LQ_GENERATE", "LQ_REGENERATE");

    /** item 终态集合（CAS notIn 使用）。 */
    private static final List<ManagementTaskStatus> TERMINAL_ITEM_STATUSES = List.of(
            ManagementTaskStatus.CANCELLED, ManagementTaskStatus.SUCCEEDED,
            ManagementTaskStatus.PARTIALLY_SUCCEEDED, ManagementTaskStatus.FAILED);

    /**
     * LQ 结果可应用的媒体状态。
     * QUEUED/GENERATING 为本次 attempt 处理中；FAILED 为上一次 attempt 失败后重试再生成，
     * 重试完成事件必须能应用到 FAILED 页面，否则重试链路永远失败。READY/NOT_GENERATED/MISSING
     * 拒绝——READY 即重复结果（幂等保护），NOT_GENERATED/MISSING 不属于本次任务范围。
     */
    private static final Set<LqStatus> LQ_APPLYABLE_MEDIA_STATES =
            EnumSet.of(LqStatus.QUEUED, LqStatus.GENERATING, LqStatus.FAILED);

    private final ManagementTaskService managementTaskService;
    private final InboxService inboxService;
    private final MediaMapper mediaMapper;
    private final ChapterMapper chapterMapper;
    private final ComicMapper comicMapper;
    private final CatalogMapper catalogMapper;
    private final ComicTagMapper comicTagMapper;
    private final ReadingHistoryMapper readingHistoryMapper;
    private final TrashManifestService trashManifestService;
    private final com.comicatlas.api.comic.cache.CatalogCacheInvalidator catalogCacheInvalidator;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;
    private final UploadSessionMapper uploadSessionMapper;
    private final UploadSessionService uploadSessionService;
    private final MediaMetadataSyncService mediaMetadataSyncService;
    private final MqConsumerSupport mqConsumerSupport;
    private final ManagementTaskItemMapper managementTaskItemMapper;
    private final MetadataRefreshService metadataRefreshService;
    private final OutboxService outboxService;
    private final ApiStorageProperties apiStorageProperties;

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
            mqConsumerSupport.consume(channel, tag, "元数据刷新完成: itemId=" + ev.itemId(),
                    () -> handleMetadataRefreshCompleted(ev));
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
        if ("TRANSCODE".equals(ev.operationType())) {
            // 转码完成走专用流程：校验目标/归属/attempt/containment 通过后才 CAS 并一次落库真实产物
            handleTranscodeCompleted(ev);
            return;
        }
        if (LQ_OPS.contains(ev.operationType())) {
            // LQ 完成走专用流程：先校验逐媒体 payload，再 item CAS 抢占并逐媒体落库（见 handleLqCompleted）
            handleLqCompleted(ev);
            return;
        }
        ManagementTaskItemResponse item = managementTaskService.updateItemStatus(
                ev.itemId(), ManagementTaskStatus.SUCCEEDED, null, null, null, ev.attempt());
        if (item.getStatus() != ManagementTaskStatus.SUCCEEDED) {
            log.info("completed 结果未生效（旧 attempt/已终态）: itemId={}, attempt={}", ev.itemId(), ev.attempt());
            return;
        }
        applyCompletedBusiness(ev);
    }

    private void applyCompletedBusiness(ManagementCommandCompletedEvent ev) {
        boolean comicScope = "COMIC".equals(ev.targetType());
        switch (ev.operationType()) {
            case "HQ_DELETE" -> {
                if (comicScope) {
                    for (Long chId : chapterIdsOf(ev.targetId())) {
                        applyHqDeleteCompleted(chId);
                    }
                } else {
                    applyHqDeleteCompleted(ev.targetId());
                }
            }
            case "COMIC_DELETE" -> applyComicTrashCompleted(ev.targetId());
            case "CHAPTER_TRASH" -> applyChapterTrashCompleted(ev.targetId());
            case "MEDIA_TRASH" -> applyMediaTrashCompleted(ev);
            case "COMIC_RESTORE" -> applyComicRestoreCompleted(ev.targetId());
            case "CHAPTER_RESTORE" -> applyChapterRestoreCompleted(ev.targetId());
            case "MEDIA_RESTORE" -> applyMediaRestoreCompleted(ev.targetId());
            case "COMIC_PURGE" -> applyComicPurgeCompleted(ev.targetId());
            case "CHAPTER_PURGE" -> applyChapterPurgeCompleted(ev.targetId());
            case "MEDIA_PURGE" -> applyMediaPurgeCompleted(ev.targetId());
            case "METADATA_REFRESH" -> {
                // 元数据刷新完成由专用事件 MetadataRefreshScanCompletedEvent 走专用流程
                // （见 handleMetadataRefreshCompleted）；此处仅防御性兜底，避免通用 completed
                // 分支把 business.run() 整体包进事务导致快照读取进入事务。
                log.warn("收到通用 completed 事件携带 METADATA_REFRESH（应走专用完成事件）: comicId={}",
                        ev.targetId());
            }
            default -> log.warn("未知 completed 操作类型: {}", ev.operationType());
        }
    }

    /** 漫画 ID → 章节 ID 列表（批量操作创建的 COMIC 目标 item 需要展开到章节处理业务状态）。 */
    private List<Long> chapterIdsOf(Long comicId) {
        return chapterMapper.selectList(new LambdaQueryWrapper<Chapter>().eq(Chapter::getComicId, comicId))
                .stream()
                .map(Chapter::getId)
                .toList();
    }

    /**
     * LQ 结果媒体归属校验：CHAPTER 目标 → media.chapterId == targetId；
     * COMIC 目标 → media 经 chapter.comicId 归属目标漫画（批量 LQ 跨章节结果，修复 P1 审核缺陷
     * 前 validate 把 targetId 当 chapterId 导致跨章节结果全被拒）。
     */
    private void validateLqTargetMembership(boolean comicScope, Long targetId,
                                            LqMediaResult result, Media media) {
        if (comicScope) {
            Long mediaChapterId = media.getChapterId();
            Chapter chapter = mediaChapterId == null ? null : chapterMapper.selectById(mediaChapterId);
            if (chapter == null || !targetId.equals(chapter.getComicId())) {
                throw new BusinessException("LQ 结果 mediaId=" + result.mediaId()
                        + " 不属于目标漫画 " + targetId);
            }
        } else if (!targetId.equals(media.getChapterId())) {
            throw new BusinessException("LQ 结果 mediaId=" + result.mediaId()
                    + " 不属于目标章节 " + targetId);
        }
    }

    /**
     * LQ 业务更新作用域：按 targetType 解析媒体范围，进度/失败/完成逐媒体更新共用。
     * CHAPTER 目标 → chapter_id = targetId；COMIC 目标 → 目标漫画下全部章节（经 chapter.comic_id 归属）。
     * COMIC 无任何章节时附加必然不命中的 id=-1 约束，避免全表更新。
     */
    private LambdaUpdateWrapper<Media> lqMediaScope(String targetType, Long targetId) {
        LambdaUpdateWrapper<Media> wrapper = new LambdaUpdateWrapper<>();
        if ("COMIC".equals(targetType)) {
            List<Long> chapterIds = chapterIdsOf(targetId);
            if (chapterIds.isEmpty()) {
                wrapper.eq(Media::getId, -1L);
            } else {
                wrapper.in(Media::getChapterId, chapterIds);
            }
        } else {
            wrapper.eq(Media::getChapterId, targetId);
        }
        return wrapper;
    }

    /**
     * LQ 完成事件逐媒体落库（Todo 6，取代旧的整章统一改状态）。
     * <p>
     * 顺序：幂等前置检查（幽灵 item / 旧 attempt / 已终态直接 ACK）→ 校验 LQ payload
     * （operation/目标章节、mediaId 归属、mediaType、pageNumber、sourceHqPath、相对结果路径、
     * 当前 LQ 状态）→ item CAS 抢占结果应用权 → 逐媒体批量落库 → 任务聚合。
     * <p>
     * 失败分类：payload 业务错误（lqResult 缺失/校验失败）→ 当前 item FAILED 并 ACK；
     * 数据库/基础设施异常 → 异常传播给 {@link MqConsumerSupport} 走重试/DLQ。
     */
    private void handleLqCompleted(ManagementCommandCompletedEvent ev) {
        ManagementTaskItem item = managementTaskItemMapper.selectById(ev.itemId());
        if (item == null) {
            log.info("LQ 完成事件引用不存在的 item，忽略: itemId={}", ev.itemId());
            return;
        }
        if (item.getAttempt() != null && !item.getAttempt().equals(ev.attempt())) {
            log.info("LQ 完成事件旧 attempt，忽略: itemId={}, event={}, item={}",
                    ev.itemId(), ev.attempt(), item.getAttempt());
            return;
        }
        if (item.getStatus() != null && item.getStatus().isTerminal()) {
            log.info("LQ 完成事件 item 已终态 {}，幂等跳过: itemId={}", item.getStatus(), ev.itemId());
            return;
        }
        if (item.getOperationType() == null || !LQ_OPS.contains(item.getOperationType().name())) {
            failLqItem(ev, "LQ 完成事件 operation 与 item 不匹配: itemId=" + ev.itemId()
                    + ", op=" + item.getOperationType());
            return;
        }
        LqGenerationResult lqResult = ev.lqResult();
        if (lqResult == null) {
            // 旧协议/无效 payload：明确失败，不猜整章结果
            failLqItem(ev, "LQ 完成事件缺少逐媒体结果（旧协议），明确失败");
            return;
        }
        try {
            validateLqResult(ev, lqResult);
        } catch (BusinessException e) {
            // payload 业务错误 → item FAILED + ACK，不重试不进 DLQ
            failLqItem(ev, e.getMessage());
            return;
        }
        applyLqResult(ev, lqResult);
    }

    /**
     * 校验 LQ 逐媒体结果与事件/DB 的一致性。任一校验失败抛 {@link BusinessException}
     * （业务 payload 错误，由调用方置 item FAILED 并 ACK）：
     * <ul>
     *   <li>目标非空、逐媒体结果非空；</li>
     *   <li>mediaId 非 null 且归属目标（CHAPTER → 目标章节；COMIC → 经 chapter 归属目标漫画）、
     *       mediaType == IMAGE、pageNumber 与 DB 一致；</li>
     *   <li>sourceHqPath 与 DB hqPath 归一化后一致（不一致视为越权/漂移）；</li>
     *   <li>READY 结果的 lqRoot/lqPath 相对且 resolve 后位于对应存储根内（containment）；</li>
     *   <li>当前媒体 lqStatus 可应用（QUEUED/GENERATING/FAILED，幂等保护）。</li>
     * </ul>
     */
    private void validateLqResult(ManagementCommandCompletedEvent ev, LqGenerationResult lqResult) {
        Long targetId = ev.targetId();
        if (targetId == null) {
            throw new BusinessException("LQ 完成事件缺少目标");
        }
        boolean comicScope = "COMIC".equals(ev.targetType());
        List<LqMediaResult> results = lqResult.results();
        if (results == null || results.isEmpty()) {
            throw new BusinessException("LQ 完成事件逐媒体结果为空");
        }
        for (LqMediaResult result : results) {
            if (result.mediaId() == null) {
                throw new BusinessException("LQ 结果缺少 mediaId");
            }
            Media media = mediaMapper.selectById(result.mediaId());
            if (media == null) {
                throw new BusinessException("LQ 结果引用不存在的媒体: mediaId=" + result.mediaId());
            }
            validateLqTargetMembership(comicScope, targetId, result, media);
            if (!"IMAGE".equals(media.getMediaType())) {
                throw new BusinessException("LQ 结果 mediaId=" + result.mediaId() + " 非 IMAGE 类型");
            }
            if (media.getPageNumber() == null || media.getPageNumber() != result.pageNumber()) {
                throw new BusinessException("LQ 结果 mediaId=" + result.mediaId() + " pageNumber 与 DB 不一致");
            }
            if (result.sourceHqPath() == null
                    || !normalizeHqPath(result.sourceHqPath()).equals(normalizeHqPath(media.getHqPath()))) {
                throw new BusinessException("LQ 结果 mediaId=" + result.mediaId()
                        + " sourceHqPath 与 DB hqPath 不一致");
            }
            if (LqMediaResult.STATUS_READY.equals(result.status())) {
                validateLqResultPath(result);
            }
            if (media.getLqStatus() == null || !LQ_APPLYABLE_MEDIA_STATES.contains(media.getLqStatus())) {
                throw new BusinessException("LQ 结果 mediaId=" + result.mediaId()
                        + " 当前 lqStatus=" + media.getLqStatus() + " 不可应用（幂等保护）");
            }
        }
    }

    /** READY 结果的 lqRoot/lqPath containment 校验：resolve 后必须位于对应存储根内（内建 ../ 穿越防御）。 */
    private void validateLqResultPath(LqMediaResult result) {
        String lqRoot = result.lqRoot();
        String lqPath = result.lqPath();
        if (lqRoot == null || lqPath == null) {
            throw new BusinessException("LQ READY 结果缺少 lqRoot/lqPath: mediaId=" + result.mediaId());
        }
        try {
            apiStorageProperties.root(lqRoot).resolve(lqPath);
        } catch (PathTraversalException e) {
            throw new BusinessException("LQ 结果路径越界: mediaId=" + result.mediaId()
                    + ", lqPath=" + lqPath, e);
        }
    }

    /**
     * 以 itemId + attempt + 非终态 CAS 抢占结果应用权，仅 1 行者进入同一短事务批量更新。
     * <p>
     * 抢占成功后逐媒体落库并聚合任务；0 行 = 已被其他 eventId 处理 → 幂等跳过（ACK）。
     * item 终态按媒体结果派生：全 READY → SUCCEEDED；混合 → PARTIALLY_SUCCEEDED；全 FAILED → FAILED；
     * progress=(READY+FAILED)/total。
     */
    private void applyLqResult(ManagementCommandCompletedEvent ev, LqGenerationResult lqResult) {
        int successCount = lqResult.successCount();
        int failureCount = lqResult.failureCount();
        int totalCount = lqResult.totalCount();
        ManagementTaskStatus itemStatus;
        if (failureCount == 0) {
            itemStatus = ManagementTaskStatus.SUCCEEDED;
        } else if (successCount == 0) {
            itemStatus = ManagementTaskStatus.FAILED;
        } else {
            itemStatus = ManagementTaskStatus.PARTIALLY_SUCCEEDED;
        }
        int itemProgress = totalCount > 0 ? (successCount + failureCount) * 100 / totalCount : 100;

        LambdaUpdateWrapper<ManagementTaskItem> cas = new LambdaUpdateWrapper<ManagementTaskItem>()
                .eq(ManagementTaskItem::getId, ev.itemId())
                .eq(ManagementTaskItem::getAttempt, ev.attempt())
                .notIn(ManagementTaskItem::getStatus, TERMINAL_ITEM_STATUSES)
                .set(ManagementTaskItem::getStatus, itemStatus)
                .set(ManagementTaskItem::getProgress, itemProgress)
                .set(ManagementTaskItem::getCompletedAt, LocalDateTime.now())
                .set(ManagementTaskItem::getLockKey, null)
                .set(ManagementTaskItem::getUpdatedAt, LocalDateTime.now());
        if (itemStatus != ManagementTaskStatus.SUCCEEDED) {
            cas.set(ManagementTaskItem::getErrorMessage, buildLqFailureSummary(lqResult));
        }
        int rows = managementTaskItemMapper.update(null, cas);
        if (rows == 0) {
            log.info("LQ item 已被其他事件置终态，幂等跳过 apply: itemId={}", ev.itemId());
            return;
        }

        // 逐媒体落库（事务内禁止文件 IO，lqSize 直接用 payload 值）
        for (LqMediaResult result : lqResult.results()) {
            applyLqMediaResult(result);
        }
        managementTaskService.reaggregateTask(ev.taskId());
    }

    /** 单媒体落库：READY 页写 lq_status/root/path/size；FAILED 页只置 lq_status。 */
    private void applyLqMediaResult(LqMediaResult result) {
        LambdaUpdateWrapper<Media> update = new LambdaUpdateWrapper<Media>()
                .eq(Media::getId, result.mediaId())
                .in(Media::getLqStatus, LQ_APPLYABLE_MEDIA_STATES);
        if (LqMediaResult.STATUS_READY.equals(result.status())) {
            update.set(Media::getLqStatus, LqStatus.READY)
                    .set(Media::getLqRoot, result.lqRoot())
                    .set(Media::getLqPath, result.lqPath())
                    .set(Media::getLqSize, result.lqSize());
        } else {
            update.set(Media::getLqStatus, LqStatus.FAILED);
        }
        int rows = mediaMapper.update(null, update);
        if (rows == 0) {
            log.warn("LQ 媒体更新未命中（状态漂移，幂等跳过）: mediaId={}", result.mediaId());
        }
    }

    /**
     * LQ 完成事件业务失败（payload 校验错误/旧协议）：item（attempt + 非终态 CAS）置 FAILED
     * 并聚合任务，不抛异常 → ACK。0 行 = 旧 attempt/已终态 → 幂等跳过。
     */
    private void failLqItem(ManagementCommandCompletedEvent ev, String errorMessage) {
        log.warn("LQ 完成事件业务失败，item FAILED 并 ACK: itemId={}, error={}", ev.itemId(), errorMessage);
        int rows = managementTaskItemMapper.update(null, new LambdaUpdateWrapper<ManagementTaskItem>()
                .eq(ManagementTaskItem::getId, ev.itemId())
                .eq(ManagementTaskItem::getAttempt, ev.attempt())
                .notIn(ManagementTaskItem::getStatus, TERMINAL_ITEM_STATUSES)
                .set(ManagementTaskItem::getStatus, ManagementTaskStatus.FAILED)
                .set(ManagementTaskItem::getErrorMessage, errorMessage)
                .set(ManagementTaskItem::getCompletedAt, LocalDateTime.now())
                .set(ManagementTaskItem::getLockKey, null)
                .set(ManagementTaskItem::getUpdatedAt, LocalDateTime.now()));
        if (rows == 0) {
            log.info("LQ 失败结果未生效（旧 attempt/已终态）: itemId={}", ev.itemId());
            return;
        }
        managementTaskService.reaggregateTask(ev.taskId());
    }

    /** 失败媒体摘要：mediaId + errorCode + errorMessage，供 item/task 记录与展示。 */
    private static String buildLqFailureSummary(LqGenerationResult lqResult) {
        List<LqMediaResult> failures = lqResult.results().stream()
                .filter(result -> LqMediaResult.STATUS_FAILED.equals(result.status()))
                .toList();
        if (failures.isEmpty()) {
            return null;
        }
        String detail = failures.stream()
                .map(f -> "mediaId=" + f.mediaId()
                        + (f.errorCode() != null ? "[" + f.errorCode() + "]" : "")
                        + (f.errorMessage() != null ? " " + f.errorMessage() : ""))
                .collect(Collectors.joining("; "));
        String summary = "LQ 失败 " + failures.size() + "/" + lqResult.totalCount() + ": " + detail;
        return summary.length() > 4000 ? summary.substring(0, 4000) : summary;
    }

    /** HQ 相对路径归一化（统一正斜杠、去首部斜杠），供 sourceHqPath 与 DB hqPath 比对。 */
    private static String normalizeHqPath(String path) {
        if (path == null) {
            return null;
        }
        String normalized = path.replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }

    private void applyHqDeleteCompleted(Long chapterId) {
        List<Media> mediaItems = mediaMapper.selectList(
                new LambdaQueryWrapper<Media>()
                        .eq(Media::getChapterId, chapterId)
                        .eq(Media::getMediaType, "IMAGE")
                        .in(Media::getHqStatus, HqStatus.READY, HqStatus.DELETE_QUEUED, HqStatus.DELETING, HqStatus.MISSING));
        for (Media media : mediaItems) {
            mediaMapper.update(null, new LambdaUpdateWrapper<Media>()
                    .eq(Media::getId, media.getId())
                    .set(Media::getHqStatus, HqStatus.DELETED)
                    .set(Media::getHqRoot, null)
                    .set(Media::getHqPath, null));
        }
        recomputeComicHqSize(chapterId);
        log.info("HQ 删除完成业务更新: chapterId={}, pages={}", chapterId, mediaItems.size());
    }

    /**
     * 转码完成专用流程（Todo 7 新契约）。
     * <p>
     * 校验顺序（全部通过才 CAS 落库）：目标必须为 MEDIA → item 归属/attempt/非终态 →
     * targetId 与 media 一致且媒体为 VIDEO → 事件携带真实产物路径 → hqPath containment
     * （必须位于对应存储根内，越界视为业务错误 → item FAILED + ACK）。
     * <p>
     * 通过后：item CAS（attempt + 非终态）置 SUCCEEDED → 一次更新 media 的 hqRoot/hqPath、
     * width/height/duration/container/codecs/fileSize、hqStatus READY、transcodeStatus READY
     * → 任务聚合 → 全部完成时触发一次整本统计。
     * <p>
     * 幂等：Inbox 复用 + item CAS 0 行（旧 attempt/已终态/并发胜者已处理）→ 零更新 ACK。
     * 业务失败（目标不匹配/路径越界/缺产物）→ item FAILED，保留可诊断摘要与媒体原 HQ 引用。
     * 基础设施异常（DB 等）向上传播 → reject/DLQ，不伪造成功。
     */
    private void handleTranscodeCompleted(ManagementCommandCompletedEvent ev) {
        if (!"MEDIA".equals(ev.targetType())) {
            failTranscodeItem(ev, null, "转码完成事件目标类型必须为 MEDIA: " + ev.targetType());
            return;
        }
        Long mediaId = ev.targetId();

        ManagementTaskItem item = managementTaskItemMapper.selectById(ev.itemId());
        if (item == null) {
            log.info("转码完成事件引用不存在的 item，忽略: itemId={}", ev.itemId());
            return;
        }
        if (item.getAttempt() != null && !item.getAttempt().equals(ev.attempt())) {
            log.info("转码完成事件 attempt 不匹配，忽略旧 attempt 结果: itemId={}, event={}, item={}",
                    ev.itemId(), ev.attempt(), item.getAttempt());
            return;
        }
        if (item.getStatus() != null && item.getStatus().isTerminal()) {
            log.info("转码完成事件 item 已终态 {}，幂等跳过: itemId={}", item.getStatus(), ev.itemId());
            return;
        }
        if (item.getTargetId() == null || !item.getTargetId().equals(mediaId)) {
            failTranscodeItem(ev, mediaId, "转码完成事件 targetId 与 item 不一致: itemTarget="
                    + item.getTargetId() + ", event=" + mediaId);
            return;
        }

        Media media = mediaMapper.selectById(mediaId);
        if (media == null || !"VIDEO".equals(media.getMediaType())) {
            failTranscodeItem(ev, mediaId, "转码完成事件媒体不存在或非视频: mediaId=" + mediaId);
            return;
        }

        TranscodeMediaInfo transcode = ev.transcode();
        if (transcode == null || transcode.hqRoot() == null
                || transcode.hqPath() == null || transcode.hqPath().isBlank()) {
            failTranscodeItem(ev, mediaId, "转码完成事件缺少真实产物路径（hqRoot/hqPath）: mediaId=" + mediaId);
            return;
        }

        // 路径 containment：hqPath 必须位于对应存储根内（resolve 内建 ../ 穿越防御）
        String rootKey = transcode.hqRoot();
        try {
            apiStorageProperties.root(rootKey).resolve(transcode.hqPath());
        } catch (Exception e) {
            failTranscodeItem(ev, mediaId, "转码产物路径越界或存储根未配置: root=" + rootKey
                    + ", hqPath=" + transcode.hqPath() + ", " + e.getMessage());
            return;
        }

        // item CAS：当前 attempt 非终态 → SUCCEEDED；0 行 = 已被其他 eventId 处理 → 幂等跳过
        int rows = managementTaskItemMapper.update(null, new LambdaUpdateWrapper<ManagementTaskItem>()
                .eq(ManagementTaskItem::getId, ev.itemId())
                .eq(ManagementTaskItem::getAttempt, ev.attempt())
                .notIn(ManagementTaskItem::getStatus, ManagementTaskStatus.CANCELLED,
                        ManagementTaskStatus.SUCCEEDED, ManagementTaskStatus.PARTIALLY_SUCCEEDED,
                        ManagementTaskStatus.FAILED)
                .set(ManagementTaskItem::getStatus, ManagementTaskStatus.SUCCEEDED)
                .set(ManagementTaskItem::getCompletedAt, LocalDateTime.now())
                .set(ManagementTaskItem::getLockKey, null)
                .set(ManagementTaskItem::getUpdatedAt, LocalDateTime.now()));
        if (rows == 0) {
            log.info("转码完成 item 已被其他 eventId 置为终态，幂等跳过 apply: itemId={}", ev.itemId());
            return;
        }

        // 一次更新 media：真实产物引用（hqRoot/hqPath）+ 全部视频元数据 + hqStatus READY + transcodeStatus READY
        mediaMapper.update(null, new LambdaUpdateWrapper<Media>()
                .eq(Media::getId, mediaId)
                .set(Media::getHqRoot, rootKey)
                .set(Media::getHqPath, transcode.hqPath())
                .set(Media::getWidth, transcode.width())
                .set(Media::getHeight, transcode.height())
                .set(Media::getDuration, transcode.duration())
                .set(Media::getContainer, transcode.container())
                .set(Media::getVideoCodec, transcode.videoCodec())
                .set(Media::getAudioCodec, transcode.audioCodec())
                .set(Media::getFileSize, transcode.fileSize())
                .set(Media::getHqStatus, HqStatus.READY)
                .set(Media::getTranscodeStatus, TranscodeStatus.READY));
        log.info("转码完成业务更新: mediaId={}, hqPath={}", mediaId, transcode.hqPath());

        managementTaskService.reaggregateTask(ev.taskId());
        maybeNotifyTranscodeTaskCompleted(ev);
    }

    /**
     * 转码完成事件业务失败：item（attempt + 非终态 CAS）置 FAILED 并聚合任务；
     * 媒体仍在 QUEUED/TRANSCODING 时置 FAILED（保留原 HQ 引用与可诊断摘要），避免卡死状态。
     * 不抛异常 → ACK（业务失败即结果，不重试不进 DLQ）。
     */
    private void failTranscodeItem(ManagementCommandCompletedEvent ev, Long mediaId, String errorMessage) {
        log.warn("转码完成事件业务失败，置 item FAILED: itemId={}, error={}", ev.itemId(), errorMessage);
        int rows = managementTaskItemMapper.update(null, new LambdaUpdateWrapper<ManagementTaskItem>()
                .eq(ManagementTaskItem::getId, ev.itemId())
                .eq(ManagementTaskItem::getAttempt, ev.attempt())
                .notIn(ManagementTaskItem::getStatus, ManagementTaskStatus.CANCELLED,
                        ManagementTaskStatus.SUCCEEDED, ManagementTaskStatus.PARTIALLY_SUCCEEDED,
                        ManagementTaskStatus.FAILED)
                .set(ManagementTaskItem::getStatus, ManagementTaskStatus.FAILED)
                .set(ManagementTaskItem::getErrorMessage, errorMessage)
                .set(ManagementTaskItem::getCompletedAt, LocalDateTime.now())
                .set(ManagementTaskItem::getLockKey, null)
                .set(ManagementTaskItem::getUpdatedAt, LocalDateTime.now()));
        if (rows == 0) {
            log.info("转码完成失败项 CAS 0 行（已终态/旧 attempt），幂等跳过: itemId={}", ev.itemId());
            return;
        }
        if (mediaId != null) {
            mediaMapper.update(null, new LambdaUpdateWrapper<Media>()
                    .eq(Media::getId, mediaId)
                    .in(Media::getTranscodeStatus, TranscodeStatus.QUEUED, TranscodeStatus.TRANSCODING)
                    .set(Media::getTranscodeStatus, TranscodeStatus.FAILED));
        }
        managementTaskService.reaggregateTask(ev.taskId());
    }

    /**
     * 转码任务全部完成（无剩余未完成项）时，触发一次整本元数据同步
     * （聚合统计 + 重导出 metadata.json），避免每个视频各自聚合与重导出。
     */
    private void maybeNotifyTranscodeTaskCompleted(ManagementCommandCompletedEvent ev) {
        if (managementTaskService.countActiveItems(ev.taskId()) > 0) {
            log.debug("转码任务仍有未完成项，跳过元数据同步: taskId={}", ev.taskId());
            return;
        }
        if ("COMIC".equals(ev.targetType())) {
            mediaMetadataSyncService.notifyTaskTranscoded(ev.targetId(), ev.taskId());
        } else {
            mediaMetadataSyncService.notifyTranscoded(ev.targetId(), ev.taskId());
        }
    }

    private void applyComicTrashCompleted(Long comicId) {
        Comic comic = comicMapper.selectById(comicId);
        if (comic != null && comic.getStatus() != ComicStatus.DELETED) {
            comic.setStatus(ComicStatus.TRASHED);
            comic.setTrashedAt(LocalDateTime.now());
            comicMapper.updateById(comic);
            catalogCacheInvalidator.evict(comicId);
            log.info("整本回收完成业务更新（回收站）: comicId={}", comicId);
        }
    }

    /**
     * 章节回收完成：status → TRASHED。媒体引用不变（文件在 TRASH，恢复时移回）。
     */
    private void applyChapterTrashCompleted(Long chapterId) {
        Chapter chapter = chapterMapper.selectById(chapterId);
        if (chapter != null && chapter.getStatus() != ChapterLifecycleStatus.DELETED) {
            chapter.setStatus(ChapterLifecycleStatus.TRASHED);
            chapter.setTrashedAt(LocalDateTime.now());
            chapterMapper.updateById(chapter);
            catalogCacheInvalidator.evict(chapter.getComicId());
            log.info("章节回收完成业务更新: chapterId={}", chapterId);
        }
    }

    /**
     * 媒体回收完成：status → TRASHED，HQ 引用指向 TRASH（保留恢复路径）。
     */
    private void applyMediaTrashCompleted(ManagementCommandCompletedEvent ev) {
        Long mediaId = ev.targetId();
        Media media = mediaMapper.selectById(mediaId);
        if (media == null) {
            return;
        }
        Long chapterId = media.getChapterId();
        String originalHqPath = media.getHqPath();
        LambdaUpdateWrapper<Media> mediaUpdate = new LambdaUpdateWrapper<Media>()
                .eq(Media::getId, mediaId)
                .set(Media::getStatus, MediaLifecycleStatus.TRASHED)
                .set(Media::getTrashedAt, LocalDateTime.now())
                .set(Media::getHqStatus, HqStatus.DELETED);
        if (originalHqPath != null && !originalHqPath.isBlank()) {
            String trashRef = "media/" + mediaId + "/" + ev.taskId() + "/hq/" + originalHqPath;
            mediaUpdate.set(Media::getHqRoot, "TRASH").set(Media::getHqPath, trashRef);
        } else {
            mediaUpdate.set(Media::getHqRoot, null).set(Media::getHqPath, null);
        }
        mediaMapper.update(null, mediaUpdate);
        refreshChapterAndComicStats(chapterId);
        Chapter chapter = chapterMapper.selectById(chapterId);
        if (chapter != null) {
            catalogCacheInvalidator.evict(chapter.getComicId());
        }
        log.info("媒体回收完成业务更新: mediaId={}", mediaId);
    }

    // ======================== 恢复 Completed ========================

    private void applyComicRestoreCompleted(Long comicId) {
        Comic comic = comicMapper.selectById(comicId);
        if (comic != null && comic.getStatus() == ComicStatus.RESTORING) {
            comic.setStatus(ComicStatus.READY);
            comic.setTrashedAt(null);
            comicMapper.updateById(comic);
            catalogCacheInvalidator.evict(comicId);
            log.info("漫画恢复完成业务更新: comicId={}", comicId);
        }
    }

    private void applyChapterRestoreCompleted(Long chapterId) {
        Chapter chapter = chapterMapper.selectById(chapterId);
        if (chapter != null && chapter.getStatus() == ChapterLifecycleStatus.RESTORING) {
            chapter.setStatus(ChapterLifecycleStatus.READY);
            chapter.setTrashedAt(null);
            chapterMapper.updateById(chapter);
            catalogCacheInvalidator.evict(chapter.getComicId());
            log.info("章节恢复完成业务更新: chapterId={}", chapterId);
        }
    }

    /**
     * 媒体恢复完成：HQ 引用移回原路径，pageNumber 复用 original_page_number，
     * 槽位被占用时事务内插入到首个合法空位。
     */
    private void applyMediaRestoreCompleted(Long mediaId) {
        Media media = mediaMapper.selectById(mediaId);
        if (media == null || media.getStatus() != MediaLifecycleStatus.RESTORING) {
            return;
        }
        Long chapterId = media.getChapterId();
        String originalPath = extractOriginalHqPath(media.getHqPath());
        int targetPage = media.getOriginalPageNumber() != null
                ? media.getOriginalPageNumber() : media.getPageNumber();
        targetPage = firstFreePageNumber(chapterId, targetPage, mediaId);

        mediaMapper.update(null, new LambdaUpdateWrapper<Media>()
                .eq(Media::getId, mediaId)
                .set(Media::getStatus, MediaLifecycleStatus.READY)
                .set(Media::getHqStatus, HqStatus.READY)
                .set(Media::getHqRoot, "HQ")
                .set(Media::getHqPath, originalPath)
                .set(Media::getPageNumber, targetPage)
                .set(Media::getTrashedAt, null));
        refreshChapterAndComicStats(chapterId);
        Chapter chapter = chapterMapper.selectById(chapterId);
        if (chapter != null) {
            catalogCacheInvalidator.evict(chapter.getComicId());
        }
        log.info("媒体恢复完成业务更新: mediaId={}, pageNumber={}", mediaId, targetPage);
    }

    // ======================== 永久清理 Completed ========================

    /**
     * 漫画永久清理：Worker 已清文件，级联删除子行，漫画保留 DELETED tombstone。
     */
    private void applyComicPurgeCompleted(Long comicId) {
        List<Chapter> chapters = chapterMapper.selectList(
                new LambdaQueryWrapper<Chapter>().eq(Chapter::getComicId, comicId));
        List<Long> chapterIds = chapters.stream().map(Chapter::getId).toList();
        if (!chapterIds.isEmpty()) {
            mediaMapper.delete(new LambdaQueryWrapper<Media>().in(Media::getChapterId, chapterIds));
        }
        readingHistoryMapper.delete(new LambdaQueryWrapper<ReadingHistory>()
                .eq(ReadingHistory::getComicId, comicId));
        chapterMapper.delete(new LambdaQueryWrapper<Chapter>().eq(Chapter::getComicId, comicId));
        catalogMapper.delete(new LambdaQueryWrapper<com.comicatlas.api.comic.entity.Catalog>()
                .eq(com.comicatlas.api.comic.entity.Catalog::getComicId, comicId));
        comicTagMapper.delete(new LambdaQueryWrapper<com.comicatlas.api.comic.entity.ComicTag>()
                .eq(com.comicatlas.api.comic.entity.ComicTag::getComicId, comicId));

        Comic comic = comicMapper.selectById(comicId);
        if (comic != null && comic.getStatus() == ComicStatus.PURGING) {
            comic.setStatus(ComicStatus.DELETED);
            comic.setDeletedAt(LocalDateTime.now());
            comicMapper.updateById(comic);
        }
        catalogCacheInvalidator.evict(comicId);
        log.info("漫画永久清理完成: comicId={}, chapters={}, media={}", comicId, chapters.size(), chapterIds.size());
    }

    /** 章节永久清理：删除媒体行，章节置 DELETED。 */
    private void applyChapterPurgeCompleted(Long chapterId) {
        mediaMapper.delete(new LambdaQueryWrapper<Media>().eq(Media::getChapterId, chapterId));
        Chapter chapter = chapterMapper.selectById(chapterId);
        if (chapter != null && chapter.getStatus() == ChapterLifecycleStatus.PURGING) {
            chapter.setStatus(ChapterLifecycleStatus.DELETED);
            chapterMapper.updateById(chapter);
            catalogCacheInvalidator.evict(chapter.getComicId());
        }
        log.info("章节永久清理完成: chapterId={}", chapterId);
    }

    /** 媒体永久清理：删除媒体行。 */
    private void applyMediaPurgeCompleted(Long mediaId) {
        Media media = mediaMapper.selectById(mediaId);
        Long chapterId = media != null ? media.getChapterId() : null;
        mediaMapper.deleteById(mediaId);
        if (chapterId != null) {
            refreshChapterAndComicStats(chapterId);
            Chapter chapter = chapterMapper.selectById(chapterId);
            if (chapter != null) {
                catalogCacheInvalidator.evict(chapter.getComicId());
            }
        }
        log.info("媒体永久清理完成: mediaId={}", mediaId);
    }

    // ======================== 媒体上传/替换 Completed ========================

    private void handleUploadCompleted(MediaUploadCompletedEvent ev) {
        ManagementTaskItemResponse item = managementTaskService.updateItemStatus(
                ev.itemId(), ManagementTaskStatus.SUCCEEDED, null, null, null, ev.attempt());
        if (item.getStatus() != ManagementTaskStatus.SUCCEEDED) {
            log.info("upload completed 未生效（旧 attempt/已终态）: itemId={}", ev.itemId());
            return;
        }
        applyUploadCompletedBusiness(ev);
    }

    private void applyUploadCompletedBusiness(MediaUploadCompletedEvent ev) {
        boolean replace = "MEDIA_REPLACE".equals(ev.operationType());
        for (MediaAnalysisResult result : ev.results()) {
            if (result.mediaId() == null) {
                continue;
            }
            LambdaUpdateWrapper<Media> mediaUpdate = new LambdaUpdateWrapper<Media>()
                    .eq(Media::getId, result.mediaId())
                    .set(Media::getStatus, MediaLifecycleStatus.READY)
                    .set(Media::getHqStatus, HqStatus.READY)
                    .set(Media::getWidth, result.width())
                    .set(Media::getHeight, result.height())
                    .set(Media::getFileSize, result.fileSize())
                    .set(Media::getMediaType, result.mediaType())
                    .set(Media::getDuration, result.duration())
                    .set(Media::getContainer, result.container())
                    .set(Media::getVideoCodec, result.videoCodec())
                    .set(Media::getAudioCodec, result.audioCodec());
            if (result.hqRoot() != null && !result.hqRoot().isBlank()) {
                mediaUpdate.set(Media::getHqRoot, result.hqRoot());
            }
            if (result.hqPath() != null && !result.hqPath().isBlank()) {
                mediaUpdate.set(Media::getHqPath, result.hqPath());
            }
            if (replace) {
                // 原子替换：保留 mediaId/pageNumber，重置 LQ/transcode
                mediaUpdate.set(Media::getLqStatus, LqStatus.NOT_GENERATED)
                        .set(Media::getTranscodeStatus, TranscodeStatus.NOT_NEEDED);
            }
            mediaMapper.update(null, mediaUpdate);
        }

        UploadSession session = uploadSessionMapper.selectById(ev.targetId());
        if (session != null) {
            refreshChapterAndComicStats(session.getChapterId());
            Chapter chapter = chapterMapper.selectById(session.getChapterId());
            if (chapter != null) {
                catalogCacheInvalidator.evict(chapter.getComicId());
            }
        }
        uploadSessionService.cleanupSessionAfterProcessed(ev.targetId());
        log.info("媒体上传/替换完成业务更新: op={}, targetId={}, results={}",
                ev.operationType(), ev.targetId(), ev.results().size());
    }

    // ======================== 元数据刷新完成（专用流程，不并入 generic completed 事务分支） ========================

    /**
     * 元数据扫盘刷新完成事件专用流程（METADATA_REFRESH）。
     * <p>
     * 与通用 completed 分支的差异：快照读取/校验（loadAndValidate）必须在事务外，
     * 若塞入把 {@code business.run()} 整体包进事务的 generic 分支，文件 IO 将进入事务，
     * 且业务失败无法区分「快照不可信 → FAILED + ACK」与「基础设施故障 → DLQ」。
     * <p>
     * 流程：幂等前置检查（无事务）→ 事务外校验快照 → 成功短事务（comic 释放 + item CAS
     * + 差异合并 + Inbox + Outbox 入箱 + 任务聚合）→ 提交后清理快照。
     * <p>
     * <b>幂等条件</b>：item 已终态 / attempt 不匹配 / 非 COMIC·METADATA_REFRESH 直接 ACK；
     * 同 attempt 不同 eventId 的重复完成事件由 item CAS 竞争，只有胜者 apply。
     * <p>
     * <b>成功点语义</b>：成功短事务提交即管理任务成功点——此后 metadata 重导出通过
     * Outbox（{@link MetadataRefreshEvent} 入箱，relay 后发 MQ），不再依赖
     * {@link MediaMetadataSyncService} 中吞异常的直接 publish。
     * <p>
     * <b>失败区分</b>：业务错误（摘要/schema/目标/数量/结构漂移）→ 独立短事务 item/task
     * FAILED、comic READY、Inbox 记录后 ACK 并保留快照；基础设施故障（DB/Outbox/
     * 快照文件不可用）→ 异常抛给 {@link MqConsumerSupport} reject 进 DLQ，不伪造成功。
     */
    private void handleMetadataRefreshCompleted(MetadataRefreshScanCompletedEvent ev) {
        String eventId = ev.eventId().toString();
        String payloadHash = sha256(toJson(ev));

        // 1. 幂等前置检查（无事务）
        ManagementTaskItem item = managementTaskItemMapper.selectById(ev.itemId());
        if (item == null) {
            log.info("元数据刷新完成事件引用不存在的 item，忽略: itemId={}", ev.itemId());
            return;
        }
        if (item.getStatus() != null && item.getStatus().isTerminal()) {
            log.info("元数据刷新完成事件 item 已终态 {}，幂等跳过: itemId={}", item.getStatus(), ev.itemId());
            return;
        }
        if (item.getAttempt() != null && !item.getAttempt().equals(ev.attempt())) {
            log.info("元数据刷新完成事件 attempt 不匹配，忽略旧 attempt 结果: itemId={}, event={}, item={}",
                    ev.itemId(), ev.attempt(), item.getAttempt());
            return;
        }
        if (item.getOperationType() != TaskType.METADATA_REFRESH || !"COMIC".equals(item.getTargetType())) {
            log.warn("元数据刷新完成事件 target/op 不匹配，防御性忽略: itemId={}, op={}, target={}",
                    ev.itemId(), item.getOperationType(), item.getTargetType());
            return;
        }
        Long comicId = item.getTargetId();

        // 2. 事务外读取并校验静态快照（SHA-256 + JSON 解析 + 结构校验）
        MetadataRefreshSnapshotDTO snapshot;
        try {
            snapshot = metadataRefreshService.loadAndValidate(
                    new MetadataRefreshLoadRequest(comicId, ev.snapshotRef(), ev.snapshotSha256(),
                            ev.snapshotBytes(), ev.schemaVersion()));
        } catch (BusinessException e) {
            if (isSnapshotIoFailure(e)) {
                throw e;
            }
            handleMetadataRefreshBusinessFailure(ev, eventId, payloadHash, e.getMessage());
            return;
        }

        // 3. 成功短事务；复核 databaseRevision 漂移抛 BusinessException → 整事务回滚 → 失败短事务
        Boolean applied;
        try {
            applied = transactionTemplate.execute(tx -> applyMetadataRefreshSuccess(ev, eventId, payloadHash, snapshot));
        } catch (BusinessException e) {
            handleMetadataRefreshBusinessFailure(ev, eventId, payloadHash, e.getMessage());
            return;
        }

        // 4. 提交后删除当前 attempt 快照目录（删除失败仅记日志不失败）
        if (Boolean.TRUE.equals(applied)) {
            deleteSnapshotDir(ev);
        }
    }

    /**
     * 成功短事务：comic 行锁 + CAS 释放 → item CAS → 差异合并 → Inbox → Outbox → 任务聚合。
     * <p>
     * item CAS 影响行数 0 表示已被其他 eventId 处理（同 attempt 重复完成事件），
     * 幂等跳过 apply/Outbox/聚合，仅记录 Inbox 后返回 false（快照由胜者清理）。
     *
     * @return true 表示本事件是本次 attempt 的 CAS 胜者并已完整 apply
     */
    private boolean applyMetadataRefreshSuccess(MetadataRefreshScanCompletedEvent ev,
                                                String eventId, String payloadHash,
                                                MetadataRefreshSnapshotDTO snapshot) {
        Long comicId = snapshot.comicId();

        // 行锁读取 + CAS 释放 REFRESHING → READY（0 行视为并发已释放，继续不失败）
        Comic locked = comicMapper.selectByIdForUpdate(comicId);
        if (locked != null && locked.getStatus() == ComicStatus.REFRESHING) {
            comicMapper.update(null, new LambdaUpdateWrapper<Comic>()
                    .eq(Comic::getId, comicId)
                    .eq(Comic::getStatus, ComicStatus.REFRESHING)
                    .set(Comic::getStatus, ComicStatus.READY));
        }

        // item CAS：当前 attempt 非终态 → SUCCEEDED；0 行 = 已被其他 eventId 处理 → 幂等跳过 apply
        int rows = managementTaskItemMapper.update(null, new LambdaUpdateWrapper<ManagementTaskItem>()
                .eq(ManagementTaskItem::getId, ev.itemId())
                .eq(ManagementTaskItem::getAttempt, ev.attempt())
                .notIn(ManagementTaskItem::getStatus, ManagementTaskStatus.CANCELLED,
                        ManagementTaskStatus.SUCCEEDED, ManagementTaskStatus.PARTIALLY_SUCCEEDED,
                        ManagementTaskStatus.FAILED)
                .set(ManagementTaskItem::getStatus, ManagementTaskStatus.SUCCEEDED)
                .set(ManagementTaskItem::getCompletedAt, LocalDateTime.now())
                .set(ManagementTaskItem::getLockKey, null)
                .set(ManagementTaskItem::getUpdatedAt, LocalDateTime.now()));
        if (rows == 0) {
            log.info("元数据刷新 item 已被其他 eventId 置为终态，幂等跳过 apply: itemId={}", ev.itemId());
            inboxService.markProcessed(eventId, payloadHash, ev.taskId(), ev.itemId(), ev.attempt());
            return false;
        }

        // 复核 databaseRevision 并执行差异合并（内部事务；漂移抛 BusinessException → 整体回滚 → 失败路径）
        metadataRefreshService.applyValidatedSnapshot(snapshot);
        catalogCacheInvalidator.evict(comicId);

        // 写 Inbox（eventId 幂等键）
        inboxService.markProcessed(eventId, payloadHash, ev.taskId(), ev.itemId(), ev.attempt());

        // metadata 重导出走 Outbox（DB→JSON，relay 后发 MQ）；禁止 MediaMetadataSyncService 吞异常的 direct publish
        outboxService.enqueue(new MetadataRefreshEvent(null, null, comicId),
                MqExchanges.EXPORT, MqRoutingKeys.METADATA_REFRESH_REQUESTED,
                ev.taskId(), ev.itemId(), ev.attempt());

        // 任务状态聚合（item 到终态，全部完成则 task SUCCEEDED）——本次提交 = 管理任务成功点
        managementTaskService.reaggregateTask(ev.taskId());
        return true;
    }

    /**
     * 业务失败短事务：item/task → FAILED（记录 errorMessage）、comic REFRESHING → READY、
     * Inbox 记录后 ACK，保留快照（供重试/排查）。
     * <p>
     * 仅当前 attempt 且非终态时生效（CAS），不影响已成功的重复事件。事务内异常向上传播 → DLQ。
     */
    private void handleMetadataRefreshBusinessFailure(MetadataRefreshScanCompletedEvent ev,
                                                      String eventId, String payloadHash, String errorMessage) {
        log.warn("元数据刷新业务失败，置 FAILED 并 ACK: itemId={}, error={}", ev.itemId(), errorMessage);
        transactionTemplate.executeWithoutResult(tx -> {
            managementTaskItemMapper.update(null, new LambdaUpdateWrapper<ManagementTaskItem>()
                    .eq(ManagementTaskItem::getId, ev.itemId())
                    .eq(ManagementTaskItem::getAttempt, ev.attempt())
                    .notIn(ManagementTaskItem::getStatus, ManagementTaskStatus.CANCELLED,
                            ManagementTaskStatus.SUCCEEDED, ManagementTaskStatus.PARTIALLY_SUCCEEDED,
                            ManagementTaskStatus.FAILED)
                    .set(ManagementTaskItem::getStatus, ManagementTaskStatus.FAILED)
                    .set(ManagementTaskItem::getErrorMessage, errorMessage)
                    .set(ManagementTaskItem::getCompletedAt, LocalDateTime.now())
                    .set(ManagementTaskItem::getLockKey, null)
                    .set(ManagementTaskItem::getUpdatedAt, LocalDateTime.now()));
            releaseComicRefreshing(ev.targetId());
            managementTaskService.reaggregateTask(ev.taskId());
            inboxService.markProcessed(eventId, payloadHash, ev.taskId(), ev.itemId(), ev.attempt());
        });
    }

    /** 提交后删除当前 attempt 快照目录（STAGING/metadata-refresh/{taskId}/{itemId}/{attempt}）。 */
    private void deleteSnapshotDir(MetadataRefreshScanCompletedEvent ev) {
        Path dir = apiStorageProperties.root("STAGING").resolve(
                "metadata-refresh/" + ev.taskId() + "/" + ev.itemId() + "/" + ev.attempt());
        if (!Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.warn("删除元数据刷新快照文件失败: {}", p, e);
                }
            });
            log.info("元数据刷新快照目录已清理: taskId={}, itemId={}, attempt={}",
                    ev.taskId(), ev.itemId(), ev.attempt());
        } catch (IOException e) {
            log.warn("元数据刷新快照目录清理失败: taskId={}, itemId={}", ev.taskId(), ev.itemId(), e);
        }
    }

    /**
     * 快照产物不可用（文件缺失/非常规文件/读取 IO 异常）视为基础设施故障 → DLQ；
     * 纯业务校验异常（SHA/schema/comicId/数量/结构漂移）走失败短事务。
     * <p>
     * 产物级故障由 {@link SnapshotUnavailableException} 类型直接标识，
     * IOException cause 兜底兼容旧链路，避免依赖错误消息文案匹配。
     */
    private boolean isSnapshotIoFailure(BusinessException e) {
        if (e instanceof SnapshotUnavailableException) {
            return true;
        }
        for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof IOException) {
                return true;
            }
        }
        return false;
    }

    /** 释放漫画元数据刷新锁（REFRESHING → READY），仅仍为 REFRESHING 时生效（CAS）。 */
    private void releaseComicRefreshing(Long comicId) {
        comicMapper.update(null, new LambdaUpdateWrapper<Comic>()
                .eq(Comic::getId, comicId)
                .eq(Comic::getStatus, ComicStatus.REFRESHING)
                .set(Comic::getStatus, ComicStatus.READY));
    }

    private void refreshChapterAndComicStats(Long chapterId) {
        if (chapterId == null) {
            return;
        }
        Chapter chapter = chapterMapper.selectById(chapterId);
        if (chapter == null) {
            return;
        }
        long pageCount = mediaMapper.selectCount(new LambdaQueryWrapper<Media>()
                .eq(Media::getChapterId, chapterId)
                .notIn(Media::getStatus, "DELETED", "TRASHED"));
        chapterMapper.update(null, new LambdaUpdateWrapper<Chapter>()
                .eq(Chapter::getId, chapterId)
                .set(Chapter::getPageCount, (int) pageCount));
        recomputeComicHqSize(chapterId);
        Comic comic = comicMapper.selectById(chapter.getComicId());
        if (comic != null) {
            List<Chapter> chapters = chapterMapper.selectList(
                    new LambdaQueryWrapper<Chapter>().eq(Chapter::getComicId, comic.getId()));
            List<Long> chapterIds = chapters.stream().map(Chapter::getId).toList();
            if (!chapterIds.isEmpty()) {
                long totalPages = mediaMapper.selectCount(new LambdaQueryWrapper<Media>()
                        .in(Media::getChapterId, chapterIds)
                        .notIn(Media::getStatus, "DELETED", "TRASHED"));
                comicMapper.update(null, new LambdaUpdateWrapper<Comic>()
                        .eq(Comic::getId, comic.getId())
                        .set(Comic::getTotalPages, (int) totalPages));
            }
        }
    }

    /**
     * 统计量从实际 page/file refs 重算：HQ 删除后重算整本 comic.hqSize。
     */
    private void recomputeComicHqSize(Long chapterId) {
        Chapter chapter = chapterMapper.selectById(chapterId);
        if (chapter == null) {
            return;
        }
        Long comicId = chapter.getComicId();
        List<Chapter> chapters = chapterMapper.selectList(
                new LambdaQueryWrapper<Chapter>().eq(Chapter::getComicId, comicId));
        if (chapters.isEmpty()) {
            return;
        }
        List<Long> chapterIds = chapters.stream().map(Chapter::getId).toList();
        List<Media> mediaItems = mediaMapper.selectList(
                new LambdaQueryWrapper<Media>().in(Media::getChapterId, chapterIds));
        long hqSize = mediaItems.stream()
                .filter(p -> p.getHqStatus() != HqStatus.DELETED)
                .mapToLong(p -> p.getFileSize() != null ? p.getFileSize() : 0L)
                .sum();
        Comic comic = comicMapper.selectById(comicId);
        if (comic != null) {
            comic.setHqSize(hqSize);
            comicMapper.updateById(comic);
        }
        log.debug("重算 comic.hqSize: comicId={}, hqSize={}", comicId, hqSize);
    }

    // ======================== Failed ========================

    private void handleFailed(ManagementCommandFailedEvent ev) {
        ManagementTaskItemResponse item = managementTaskService.updateItemStatus(
                ev.itemId(), ManagementTaskStatus.FAILED, ev.errorMessage(), null, null, ev.attempt());
        if (item.getStatus() != ManagementTaskStatus.FAILED) {
            log.info("failed 结果未生效（旧 attempt/已终态）: itemId={}, attempt={}", ev.itemId(), ev.attempt());
            return;
        }
        applyFailedBusiness(ev);
    }

    private void applyFailedBusiness(ManagementCommandFailedEvent ev) {
        switch (ev.operationType()) {
            case "LQ_GENERATE", "LQ_REGENERATE" -> {
                mediaMapper.update(null, lqMediaScope(ev.targetType(), ev.targetId())
                        .eq(Media::getMediaType, "IMAGE")
                        .in(Media::getLqStatus, LqStatus.QUEUED, LqStatus.GENERATING)
                        .set(Media::getLqStatus, LqStatus.FAILED));
            }
            case "HQ_DELETE" -> {
                mediaMapper.update(null, new LambdaUpdateWrapper<Media>()
                        .eq(Media::getChapterId, ev.targetId())
                        .in(Media::getHqStatus, HqStatus.DELETE_QUEUED, HqStatus.DELETING)
                        .set(Media::getHqStatus, HqStatus.FAILED));
            }
            case "TRANSCODE" -> {
                mediaMapper.update(null, new LambdaUpdateWrapper<Media>()
                        .eq(Media::getId, ev.targetId())
                        .in(Media::getTranscodeStatus, TranscodeStatus.QUEUED, TranscodeStatus.TRANSCODING)
                        .set(Media::getTranscodeStatus, TranscodeStatus.FAILED));
            }
            case "MEDIA_UPLOAD", "MEDIA_REPLACE" -> {
                uploadSessionMapper.update(null, new LambdaUpdateWrapper<UploadSession>()
                        .eq(UploadSession::getId, ev.targetId())
                        .set(UploadSession::getStatus, UploadSessionStatus.FAILED));
            }
            case "METADATA_REFRESH" -> releaseComicRefreshing(ev.targetId());
            case "COMIC_DELETE", "CHAPTER_TRASH", "MEDIA_TRASH" ->
                    applyTrashFailed(ev.targetType(), ev.targetId(), ev.taskId());
            case "COMIC_RESTORE", "CHAPTER_RESTORE", "MEDIA_RESTORE" ->
                    revertToTrashed(ev.targetType(), ev.targetId());
            case "COMIC_PURGE", "CHAPTER_PURGE", "MEDIA_PURGE" ->
                    revertToTrashed(ev.targetType(), ev.targetId());
            default -> { }
        }
        log.info("命令失败业务回退: itemId={}, op={}, target={}", ev.itemId(), ev.operationType(), ev.targetId());
    }

    /**
     * 回收失败：依据 actual.json 判断补偿结果。
     * COMPENSATED → 文件已全部回滚，实体回 READY；否则保持 TRASHING（可 RECONCILE/RETRY）。
     */
    private void applyTrashFailed(String targetType, Long targetId, Long taskId) {
        TrashManifestItemDTO actual = trashManifestService.readActual(targetType, targetId, taskId);
        if (actual != null && TrashManifestItemDTO.STATUS_COMPENSATED.equals(actual.status())) {
            revertToReady(targetType, targetId);
            log.info("回收补偿完成，回退 READY: {}/{}", targetType, targetId);
        } else {
            log.info("回收失败且补偿不完整，保持 TRASHING: {}/{}, actual={}",
                    targetType, targetId, actual != null ? actual.status() : null);
        }
    }

    /** 恢复/清理失败：文件仍在 TRASH，回退 TRASHED 保留 tombstone。 */
    private void revertToTrashed(String targetType, Long targetId) {
        switch (targetType) {
            case "COMIC" -> {
                Comic comic = comicMapper.selectById(targetId);
                if (comic != null && comic.getStatus() == ComicStatus.RESTORING) {
                    comic.setStatus(ComicStatus.TRASHED);
                    comicMapper.updateById(comic);
                } else if (comic != null && comic.getStatus() == ComicStatus.PURGING) {
                    comic.setStatus(ComicStatus.TRASHED);
                    comicMapper.updateById(comic);
                }
            }
            case "CHAPTER" -> {
                Chapter chapter = chapterMapper.selectById(targetId);
                if (chapter != null && (chapter.getStatus() == ChapterLifecycleStatus.RESTORING
                        || chapter.getStatus() == ChapterLifecycleStatus.PURGING)) {
                    chapter.setStatus(ChapterLifecycleStatus.TRASHED);
                    chapterMapper.updateById(chapter);
                }
            }
            case "MEDIA" -> {
                Media media = mediaMapper.selectById(targetId);
                if (media != null && (media.getStatus() == MediaLifecycleStatus.RESTORING
                        || media.getStatus() == MediaLifecycleStatus.PURGING)) {
                    media.setStatus(MediaLifecycleStatus.TRASHED);
                    mediaMapper.updateById(media);
                }
            }
            default -> { }
        }
    }

    /** 补偿完成回退 READY（回收失败但文件已全部回滚）。 */
    private void revertToReady(String targetType, Long targetId) {
        switch (targetType) {
            case "COMIC" -> {
                Comic comic = comicMapper.selectById(targetId);
                if (comic != null && comic.getStatus() == ComicStatus.TRASHING) {
                    comic.setStatus(ComicStatus.READY);
                    comic.setTrashedAt(null);
                    comicMapper.updateById(comic);
                }
            }
            case "CHAPTER" -> {
                Chapter chapter = chapterMapper.selectById(targetId);
                if (chapter != null && chapter.getStatus() == ChapterLifecycleStatus.TRASHING) {
                    chapter.setStatus(ChapterLifecycleStatus.READY);
                    chapter.setTrashedAt(null);
                    chapterMapper.updateById(chapter);
                }
            }
            case "MEDIA" -> {
                Media media = mediaMapper.selectById(targetId);
                if (media != null && media.getStatus() == MediaLifecycleStatus.TRASHING) {
                    media.setStatus(MediaLifecycleStatus.READY);
                    media.setTrashedAt(null);
                    if (media.getOriginalPageNumber() != null) {
                        media.setPageNumber(media.getOriginalPageNumber());
                    }
                    mediaMapper.updateById(media);
                }
            }
            default -> { }
        }
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
            mediaMapper.update(null, lqMediaScope(ev.targetType(), ev.targetId())
                    .eq(Media::getLqStatus, LqStatus.QUEUED)
                    .set(Media::getLqStatus, LqStatus.GENERATING));
        } else if ("HQ_DELETE".equals(ev.operationType())) {
            mediaMapper.update(null, new LambdaUpdateWrapper<Media>()
                    .eq(Media::getChapterId, ev.targetId())
                    .eq(Media::getHqStatus, HqStatus.DELETE_QUEUED)
                    .set(Media::getHqStatus, HqStatus.DELETING));
        } else if ("TRANSCODE".equals(ev.operationType())) {
            mediaMapper.update(null, new LambdaUpdateWrapper<Media>()
                    .eq(Media::getId, ev.targetId())
                    .eq(Media::getTranscodeStatus, TranscodeStatus.QUEUED)
                    .set(Media::getTranscodeStatus, TranscodeStatus.TRANSCODING));
        }
    }

    // ======================== 辅助 ========================

    /**
     * 从 TRASH 引用（media/{mediaId}/{taskId}/hq/{original}）还原原始 HQ 路径。
     */
    private static String extractOriginalHqPath(String trashRef) {
        if (trashRef == null) {
            return null;
        }
        int idx = trashRef.indexOf("/hq/");
        return idx >= 0 ? trashRef.substring(idx + 4) : null;
    }

    /**
     * 计算恢复页码：优先复用 originalPageNumber；被占用时取 1..N+1 的首个空位。
     */
    private int firstFreePageNumber(Long chapterId, int preferred, Long mediaId) {
        List<Media> existing = mediaMapper.selectList(new LambdaQueryWrapper<Media>()
                .eq(Media::getChapterId, chapterId)
                .select(Media::getId, Media::getPageNumber));
        java.util.Set<Integer> occupied = new java.util.HashSet<>();
        for (Media media : existing) {
            if (!media.getId().equals(mediaId) && media.getPageNumber() != null) {
                occupied.add(media.getPageNumber());
            }
        }
        if (!occupied.contains(preferred)) {
            return preferred;
        }
        int slot = 1;
        while (occupied.contains(slot)) {
            slot++;
        }
        return slot;
    }

    private String toJson(ComicEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("结果事件序列化失败: " + event.eventId(), e);
        }
    }

    private static String sha256(String input) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
