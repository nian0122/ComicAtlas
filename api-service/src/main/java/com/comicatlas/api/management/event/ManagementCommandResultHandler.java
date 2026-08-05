package com.comicatlas.api.management.event;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.comicatlas.api.comic.cache.CatalogCacheInvalidator;
import com.comicatlas.api.comic.entity.Chapter;
import com.comicatlas.api.comic.entity.Comic;
import com.comicatlas.api.comic.entity.Media;
import com.comicatlas.api.comic.mapper.CatalogMapper;
import com.comicatlas.api.comic.mapper.ChapterMapper;
import com.comicatlas.api.comic.mapper.ComicMapper;
import com.comicatlas.api.comic.mapper.ComicTagMapper;
import com.comicatlas.api.comic.mapper.MediaMapper;
import com.comicatlas.api.common.enums.ComicStatus;
import com.comicatlas.api.management.dto.ManagementTaskItemResponse;
import com.comicatlas.api.management.service.ManagementTaskService;
import com.comicatlas.api.management.trash.TrashManifestService;
import com.comicatlas.api.outbox.service.InboxService;
import com.comicatlas.api.reader.entity.ReadingHistory;
import com.comicatlas.api.reader.mapper.ReadingHistoryMapper;
import com.comicatlas.common.dto.TrashManifestActual;
import com.comicatlas.common.enums.ManagementTaskStatus;
import com.comicatlas.common.event.ComicEvent;
import com.comicatlas.common.event.ManagementCommandCompletedEvent;
import com.comicatlas.common.event.ManagementCommandFailedEvent;
import com.comicatlas.common.event.ManagementCommandProgressEvent;
import com.comicatlas.common.event.MediaUploadCompletedEvent;
import com.comicatlas.common.event.MediaUploadCompletedEvent.MediaAnalysisResult;
import com.comicatlas.api.upload.UploadSessionService;
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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * 管理命令结果事件处理器（Worker → API）。
 * <p>
 * 消费 {@code comic.management} 的 completed/failed/progress 事件，
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

    @RabbitListener(queues = "management.result.queue")
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
        } else {
            ack(channel, tag);
        }
    }

    private void process(ComicEvent event, Long taskId, Long itemId, int attempt,
                         Channel channel, long tag, Runnable business) {
        String eventId = event.eventId().toString();
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
            ack(channel, tag);
        } catch (DuplicateKeyException e) {
            log.warn("Inbox 并发重复结果事件，已由其他投递处理: eventId={}", eventId);
            ack(channel, tag);
        } catch (Exception e) {
            log.error("管理命令结果处理失败: eventId={}, itemId={}", eventId, itemId, e);
            reject(channel, tag);
        }
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
                        applyTranscodeCompleted(mediaId);
                    }
                } else {
                    applyTranscodeCompleted(ev.targetId());
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
            case "METADATA_REFRESH" -> { }
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
                    .set(Media::getLqStatus, "READY")
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
                        .in(Media::getHqStatus, "READY", "DELETE_QUEUED", "DELETING", "MISSING"));
        for (Media media : mediaItems) {
            mediaMapper.update(null, new LambdaUpdateWrapper<Media>()
                    .eq(Media::getId, media.getId())
                    .set(Media::getHqStatus, "DELETED")
                    .set(Media::getHqRoot, null)
                    .set(Media::getHqPath, null));
        }
        recomputeComicHqSize(chapterId);
        log.info("HQ 删除完成业务更新: chapterId={}, pages={}", chapterId, mediaItems.size());
    }

    private void applyTranscodeCompleted(Long mediaId) {
        Media media = mediaMapper.selectById(mediaId);
        if (media == null) {
            return;
        }
        String hqPath = media.getHqPath();
        LambdaUpdateWrapper<Media> mediaUpdate = new LambdaUpdateWrapper<Media>()
                .eq(Media::getId, mediaId)
                .set(Media::getTranscodeStatus, "READY")
                .set(Media::getContainer, "mp4")
                .set(Media::getVideoCodec, "h264")
                .set(Media::getAudioCodec, "aac");
        if (hqPath != null && !hqPath.isBlank()) {
            mediaUpdate.set(Media::getHqPath, deriveTranscodedPath(hqPath));
        }
        mediaMapper.update(null, mediaUpdate);
        log.info("转码完成业务更新: mediaId={}", mediaId);
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
        if (chapter != null && !"DELETED".equals(chapter.getStatus())) {
            chapter.setStatus("TRASHED");
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
                .set(Media::getStatus, "TRASHED")
                .set(Media::getTrashedAt, LocalDateTime.now())
                .set(Media::getHqStatus, "DELETED");
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
        if (chapter != null && "RESTORING".equals(chapter.getStatus())) {
            chapter.setStatus("READY");
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
        if (media == null || !"RESTORING".equals(media.getStatus())) {
            return;
        }
        Long chapterId = media.getChapterId();
        String originalPath = extractOriginalHqPath(media.getHqPath());
        int targetPage = media.getOriginalPageNumber() != null
                ? media.getOriginalPageNumber() : media.getPageNumber();
        targetPage = firstFreePageNumber(chapterId, targetPage, mediaId);

        mediaMapper.update(null, new LambdaUpdateWrapper<Media>()
                .eq(Media::getId, mediaId)
                .set(Media::getStatus, "READY")
                .set(Media::getHqStatus, "READY")
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
        if (chapter != null && "PURGING".equals(chapter.getStatus())) {
            chapter.setStatus("DELETED");
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
        for (MediaAnalysisResult r : ev.results()) {
            if (r.mediaId() == null) {
                continue;
            }
            LambdaUpdateWrapper<Media> mediaUpdate = new LambdaUpdateWrapper<Media>()
                    .eq(Media::getId, r.mediaId())
                    .set(Media::getStatus, "READY")
                    .set(Media::getHqStatus, "READY")
                    .set(Media::getWidth, r.width())
                    .set(Media::getHeight, r.height())
                    .set(Media::getFileSize, r.fileSize())
                    .set(Media::getMediaType, r.mediaType())
                    .set(Media::getDuration, r.duration())
                    .set(Media::getContainer, r.container())
                    .set(Media::getVideoCodec, r.videoCodec())
                    .set(Media::getAudioCodec, r.audioCodec());
            if (r.hqRoot() != null && !r.hqRoot().isBlank()) {
                mediaUpdate.set(Media::getHqRoot, r.hqRoot());
            }
            if (r.hqPath() != null && !r.hqPath().isBlank()) {
                mediaUpdate.set(Media::getHqPath, r.hqPath());
            }
            if (replace) {
                // 原子替换：保留 mediaId/pageNumber，重置 LQ/transcode
                mediaUpdate.set(Media::getLqStatus, "NOT_GENERATED")
                        .set(Media::getTranscodeStatus, "NOT_NEEDED");
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
                .filter(p -> !"DELETED".equals(p.getHqStatus()))
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
                        .in(Media::getLqStatus, "QUEUED", "GENERATING")
                        .set(Media::getLqStatus, "FAILED"));
            }
            case "HQ_DELETE" -> {
                mediaMapper.update(null, new LambdaUpdateWrapper<Media>()
                        .eq(Media::getChapterId, ev.targetId())
                        .in(Media::getHqStatus, "DELETE_QUEUED", "DELETING")
                        .set(Media::getHqStatus, "FAILED"));
            }
            case "TRANSCODE" -> {
                mediaMapper.update(null, new LambdaUpdateWrapper<Media>()
                        .eq(Media::getId, ev.targetId())
                        .in(Media::getTranscodeStatus, "QUEUED", "TRANSCODING")
                        .set(Media::getTranscodeStatus, "FAILED"));
            }
            case "MEDIA_UPLOAD", "MEDIA_REPLACE" -> {
                uploadSessionMapper.update(null, new LambdaUpdateWrapper<UploadSession>()
                        .eq(UploadSession::getId, ev.targetId())
                        .set(UploadSession::getStatus, "FAILED"));
            }
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
        TrashManifestActual actual = trashManifestService.readActual(targetType, targetId, taskId);
        if (actual != null && TrashManifestActual.STATUS_COMPENSATED.equals(actual.status())) {
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
                if (chapter != null && ("RESTORING".equals(chapter.getStatus()) || "PURGING".equals(chapter.getStatus()))) {
                    chapter.setStatus("TRASHED");
                    chapterMapper.updateById(chapter);
                }
            }
            case "MEDIA" -> {
                Media media = mediaMapper.selectById(targetId);
                if (media != null && ("RESTORING".equals(media.getStatus()) || "PURGING".equals(media.getStatus()))) {
                    media.setStatus("TRASHED");
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
                if (chapter != null && "TRASHING".equals(chapter.getStatus())) {
                    chapter.setStatus("READY");
                    chapter.setTrashedAt(null);
                    chapterMapper.updateById(chapter);
                }
            }
            case "MEDIA" -> {
                Media media = mediaMapper.selectById(targetId);
                if (media != null && "TRASHING".equals(media.getStatus())) {
                    media.setStatus("READY");
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
                    .eq(Media::getLqStatus, "QUEUED")
                    .set(Media::getLqStatus, "GENERATING"));
        } else if ("HQ_DELETE".equals(ev.operationType())) {
            mediaMapper.update(null, new LambdaUpdateWrapper<Media>()
                    .eq(Media::getChapterId, ev.targetId())
                    .eq(Media::getHqStatus, "DELETE_QUEUED")
                    .set(Media::getHqStatus, "DELETING"));
        } else if ("TRANSCODE".equals(ev.operationType())) {
            mediaMapper.update(null, new LambdaUpdateWrapper<Media>()
                    .eq(Media::getId, ev.targetId())
                    .eq(Media::getTranscodeStatus, "QUEUED")
                    .set(Media::getTranscodeStatus, "TRANSCODING"));
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

    private void ack(Channel channel, long tag) {
        try {
            channel.basicAck(tag, false);
        } catch (Exception e) {
            log.error("结果事件 ack 失败: tag={}", tag, e);
        }
    }

    private void reject(Channel channel, long tag) {
        try {
            channel.basicReject(tag, false);
        } catch (Exception e) {
            log.error("结果事件 reject 失败: tag={}", tag, e);
        }
    }
}
