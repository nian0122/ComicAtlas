package com.comicatlas.api.management.event;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.comicatlas.persistence.comic.entity.Chapter;
import com.comicatlas.persistence.comic.entity.Comic;
import com.comicatlas.persistence.comic.entity.Media;
import com.comicatlas.persistence.comic.mapper.CatalogMapper;
import com.comicatlas.persistence.comic.mapper.ChapterMapper;
import com.comicatlas.persistence.comic.mapper.ComicMapper;
import com.comicatlas.persistence.comic.mapper.ComicTagMapper;
import com.comicatlas.persistence.comic.mapper.MediaMapper;
import com.comicatlas.contract.common.enums.ComicStatus;
import com.comicatlas.contract.common.enums.HqStatus;
import com.comicatlas.contract.common.enums.LqStatus;
import com.comicatlas.contract.common.enums.ManagementTaskStatus;
import com.comicatlas.contract.common.enums.MediaLifecycleStatus;
import com.comicatlas.contract.common.enums.TaskType;
import com.comicatlas.contract.common.enums.TranscodeStatus;
import com.comicatlas.contract.common.exception.BusinessException;
import com.comicatlas.contract.common.exception.SnapshotUnavailableException;
import com.comicatlas.persistence.storage.ApiStorageProperties;
import com.comicatlas.api.management.dto.ManagementTaskItemResponse;
import com.comicatlas.api.management.entity.ManagementTaskItem;
import com.comicatlas.api.management.mapper.ManagementTaskItemMapper;
import com.comicatlas.api.management.service.ManagementTaskService;
import com.comicatlas.api.management.trash.TrashManifestService;
import com.comicatlas.api.outbox.service.InboxService;
import com.comicatlas.api.outbox.service.OutboxService;
import com.comicatlas.persistence.reader.entity.ReadingHistory;
import com.comicatlas.persistence.reader.mapper.ReadingHistoryMapper;
import com.comicatlas.api.storage.service.MediaMetadataSyncService;
import com.comicatlas.api.storage.service.MetadataRefreshService;
import com.comicatlas.api.storage.service.MetadataRefreshService.MetadataRefreshLoadRequest;
import com.comicatlas.common.constant.MqExchanges;
import com.comicatlas.common.constant.MqQueues;
import com.comicatlas.common.constant.MqRoutingKeys;
import com.comicatlas.common.dto.MetadataRefreshSnapshotDTO;
import com.comicatlas.common.dto.TrashManifestItemDTO;
import com.comicatlas.contract.common.enums.ChapterLifecycleStatus;
import com.comicatlas.common.event.ComicEvent;
import com.comicatlas.common.event.ManagementCommandCompletedEvent;
import com.comicatlas.common.event.ManagementCommandFailedEvent;
import com.comicatlas.common.event.ManagementCommandProgressEvent;
import com.comicatlas.common.event.MediaUploadCompletedEvent;
import com.comicatlas.common.event.MediaUploadCompletedEvent.MediaAnalysisResult;
import com.comicatlas.common.event.MetadataRefreshEvent;
import com.comicatlas.common.event.MetadataRefreshScanCompletedEvent;
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
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

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
            case "LQ_GENERATE", "LQ_REGENERATE" -> {
                if (comicScope) {
                    for (Long chId : chapterIdsOf(ev.targetId())) {
                        applyLqCompleted(chId);
                    }
                } else {
                    applyLqCompleted(ev.targetId());
                }
            }
            case "HQ_DELETE" -> {
                if (comicScope) {
                    for (Long chId : chapterIdsOf(ev.targetId())) {
                        applyHqDeleteCompleted(chId);
                    }
                } else {
                    applyHqDeleteCompleted(ev.targetId());
                }
            }
            case "TRANSCODE" -> {
                if (comicScope) {
                    for (Long mediaId : mediaIdsOf(ev.targetId())) {
                        applyTranscodeCompleted(ev, mediaId);
                    }
                } else {
                    applyTranscodeCompleted(ev, ev.targetId());
                }
                maybeNotifyTranscodeTaskCompleted(ev);
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

    /** 漫画 ID → 视频媒体 ID 列表（COMIC 目标转码 item 展开到视频页处理业务状态）。 */
    private List<Long> mediaIdsOf(Long comicId) {
        List<Long> chapterIds = chapterIdsOf(comicId);
        if (chapterIds.isEmpty()) {
            return List.of();
        }
        return mediaMapper.selectList(new LambdaQueryWrapper<Media>()
                        .in(Media::getChapterId, chapterIds)
                        .eq(Media::getMediaType, "VIDEO"))
                .stream()
                .map(Media::getId)
                .toList();
    }

    private void applyLqCompleted(Long chapterId) {
        List<Media> mediaItems = mediaMapper.selectList(
                new LambdaQueryWrapper<Media>()
                        .eq(Media::getChapterId, chapterId)
                        .eq(Media::getMediaType, "IMAGE"));
        for (Media media : mediaItems) {
            LambdaUpdateWrapper<Media> mediaUpdate = new LambdaUpdateWrapper<Media>()
                    .eq(Media::getId, media.getId())
                    .set(Media::getLqStatus, LqStatus.READY)
                    .set(Media::getLqRoot, "LQ");
            String hqPath = media.getHqPath();
            if (hqPath != null && !hqPath.isBlank()) {
                mediaUpdate.set(Media::getLqPath, deriveLqPath(hqPath));
            }
            mediaMapper.update(null, mediaUpdate);
        }
        log.info("LQ 完成业务更新: chapterId={}, pages={}", chapterId, mediaItems.size());
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
     * 转码完成业务更新：实测元数据（duration/fileSize/真实 codec）优先，
     * 事件未携带时回退旧的硬编码 mp4/h264/aac；hq_path 以事件实测
     * {@code transcode.newHqPath()} 为准（含防撞名），老消息回退
     * {@code deriveTranscodedPath}。
     * 每个完成事件都更新对应 media 行；整本统计聚合与 metadata.json 重导出
     * 由 {@link #maybeNotifyTranscodeTaskCompleted} 在任务全部完成时触发一次。
     */
    private void applyTranscodeCompleted(ManagementCommandCompletedEvent ev, Long mediaId) {
        Media media = mediaMapper.selectById(mediaId);
        if (media == null) {
            return;
        }
        TranscodeMediaInfo transcode = ev.transcode();
        String hqPath = media.getHqPath();
        LambdaUpdateWrapper<Media> mediaUpdate = new LambdaUpdateWrapper<Media>()
                .eq(Media::getId, mediaId)
                .set(Media::getTranscodeStatus, TranscodeStatus.READY)
                .set(Media::getContainer, transcode != null && transcode.container() != null
                        ? transcode.container() : "mp4")
                .set(Media::getVideoCodec, transcode != null && transcode.videoCodec() != null
                        ? transcode.videoCodec() : "h264")
                .set(Media::getAudioCodec, transcode != null && transcode.audioCodec() != null
                        ? transcode.audioCodec() : "aac");
        if (transcode != null) {
            if (transcode.duration() != null) {
                mediaUpdate.set(Media::getDuration, transcode.duration());
            }
            if (transcode.fileSize() != null) {
                mediaUpdate.set(Media::getFileSize, transcode.fileSize());
            }
        }
        if (hqPath != null && !hqPath.isBlank()) {
            // 优先使用 Worker 实测写入路径（含防撞名 {base}.transcoded-{mediaId}.mp4 场景）；
            // 老消息无 newHqPath 时回退 deriveTranscodedPath（{base}.mp4）
            String newHqPath = transcode != null && transcode.newHqPath() != null
                    && !transcode.newHqPath().isBlank()
                    ? transcode.newHqPath()
                    : deriveTranscodedPath(hqPath);
            mediaUpdate.set(Media::getHqPath, newHqPath);
        }
        mediaMapper.update(null, mediaUpdate);
        log.info("转码完成业务更新: mediaId={}", mediaId);
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
        catalogMapper.delete(new LambdaQueryWrapper<com.comicatlas.persistence.comic.entity.Catalog>()
                .eq(com.comicatlas.persistence.comic.entity.Catalog::getComicId, comicId));
        comicTagMapper.delete(new LambdaQueryWrapper<com.comicatlas.persistence.comic.entity.ComicTag>()
                .eq(com.comicatlas.persistence.comic.entity.ComicTag::getComicId, comicId));

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
                mediaMapper.update(null, new LambdaUpdateWrapper<Media>()
                        .eq(Media::getChapterId, ev.targetId())
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
            mediaMapper.update(null, new LambdaUpdateWrapper<Media>()
                    .eq(Media::getChapterId, ev.targetId())
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

    private static String deriveLqPath(String hqPath) {
        return hqPath.replaceAll("\\.[^.]+$", ".webp");
    }

    private static String deriveTranscodedPath(String hqPath) {
        int lastSlash = hqPath.lastIndexOf('/');
        String dir = lastSlash > 0 ? hqPath.substring(0, lastSlash + 1) : "";
        String name = hqPath.substring(lastSlash + 1);
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        return dir + base + ".mp4";
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
