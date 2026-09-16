package com.comicatlas.api.trash.service.impl;

import com.comicatlas.api.catalog.cache.CatalogCacheInvalidator;
import com.comicatlas.api.storage.service.ComicStatsService;
import com.comicatlas.common.constant.StorageRootKeys;
import com.comicatlas.common.dto.TrashManifestItemDTO;
import com.comicatlas.common.event.ManagementCommandCompletedEvent;
import com.comicatlas.contract.common.enums.ChapterLifecycleStatus;
import com.comicatlas.contract.common.enums.ComicStatus;
import com.comicatlas.contract.common.enums.MediaLifecycleStatus;
import com.comicatlas.persistence.comic.entity.Chapter;
import com.comicatlas.persistence.comic.entity.Comic;
import com.comicatlas.persistence.comic.entity.Media;
import com.comicatlas.persistence.comic.mapper.CatalogMapper;
import com.comicatlas.persistence.comic.mapper.ChapterMapper;
import com.comicatlas.persistence.comic.mapper.ComicMapper;
import com.comicatlas.persistence.comic.mapper.MediaMapper;
import com.comicatlas.api.trash.persistence.mapper.TrashDataMapper;
import com.comicatlas.api.trash.service.TrashLifecycleCompletionService;
import com.comicatlas.api.trash.service.TrashManifestService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 回收生命周期完成落库服务（trash / restore / purge）。
 * <p>
 * 由 {@link ManagementCommandResultHandler} 在结果事件事务内调用，
 * 依据 Worker 回传的 completed / failed 事件更新 comic/chapter/media
 * 生命周期状态；媒体级变更完成后触发 {@link ComicStatsService} 重算统计。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TrashLifecycleCompletionServiceImpl implements TrashLifecycleCompletionService {
    // 回收结果契约由应用服务公开，具体实现保持在回收业务包内。

    /** TRASH 引用前缀（media/{mediaId}/{taskId}/hq/{original}）。 */
    private static final String TRASH_REF_PREFIX = "media/";

    /** TRASH 引用中原始 HQ 路径的分隔标记。 */
    private static final String TRASH_HQ_MARKER = "/hq/";

    /** 回收卷存储根键。 */
    private static final String ROOT_KEY_TRASH = "TRASH";

    private final MediaMapper mediaMapper;
    private final ChapterMapper chapterMapper;
    private final ComicMapper comicMapper;
    private final CatalogMapper catalogMapper;
    private final TrashManifestService trashManifestService;
    private final CatalogCacheInvalidator catalogCacheInvalidator;
    private final ComicStatsService comicStatsService;
    private final TrashDataMapper trashDataMapper;

    // ======================== 回收 Completed ========================

    /** 漫画回收完成：status → TRASHED。 */
    public void applyComicTrashCompleted(Long comicId) {
        Comic comic = comicMapper.selectById(comicId);
        if (comic != null && comic.getStatus() != ComicStatus.DELETED) {
            comic.setStatus(ComicStatus.TRASHED);
            comic.setTrashedAt(LocalDateTime.now());
            comicMapper.updateById(comic);
            catalogCacheInvalidator.evict(comicId);
            log.info("整本回收完成业务更新（回收站）: comicId={}", comicId);
        }
    }

    /** 章节回收完成：status → TRASHED。媒体引用不变（文件在 TRASH，恢复时移回）。 */
    public void applyChapterTrashCompleted(Long chapterId) {
        Chapter chapter = chapterMapper.selectById(chapterId);
        if (chapter != null && chapter.getStatus() != ChapterLifecycleStatus.DELETED) {
            chapter.setStatus(ChapterLifecycleStatus.TRASHED);
            chapter.setTrashedAt(LocalDateTime.now());
            chapterMapper.updateById(chapter);
            catalogCacheInvalidator.evict(chapter.getComicId());
            log.info("章节回收完成业务更新: chapterId={}", chapterId);
        }
    }

    /** 媒体回收完成：status → TRASHED，HQ 引用指向 TRASH（保留恢复路径）。 */
    public void applyMediaTrashCompleted(ManagementCommandCompletedEvent ev) {
        Long mediaId = ev.targetId();
        Media media = mediaMapper.selectById(mediaId);
        if (media == null) {
            return;
        }
        Long chapterId = media.getChapterId();
        String originalHqPath = media.getHqPath();
        String trashRef = null;
        if (originalHqPath != null && !originalHqPath.isBlank()) {
            trashRef = TRASH_REF_PREFIX + mediaId + "/" + ev.taskId() + TRASH_HQ_MARKER + originalHqPath;
        }
        mediaMapper.markTrashed(mediaId, LocalDateTime.now(),
                trashRef == null ? null : ROOT_KEY_TRASH, trashRef);
        comicStatsService.refreshByChapter(chapterId);
        Chapter chapter = chapterMapper.selectById(chapterId);
        if (chapter != null) {
            catalogCacheInvalidator.evict(chapter.getComicId());
        }
        log.info("媒体回收完成业务更新: mediaId={}", mediaId);
    }

    // ======================== 恢复 Completed ========================

    /** 漫画恢复完成：RESTORING → READY。 */
    public void applyComicRestoreCompleted(Long comicId) {
        Comic comic = comicMapper.selectById(comicId);
        if (comic != null && comic.getStatus() == ComicStatus.RESTORING) {
            comic.setStatus(ComicStatus.READY);
            comic.setTrashedAt(null);
            comicMapper.updateById(comic);
            catalogCacheInvalidator.evict(comicId);
            log.info("漫画恢复完成业务更新: comicId={}", comicId);
        }
    }

    /** 章节恢复完成：RESTORING → READY。 */
    public void applyChapterRestoreCompleted(Long chapterId) {
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
    public void applyMediaRestoreCompleted(Long mediaId) {
        Media media = mediaMapper.selectById(mediaId);
        if (media == null || media.getStatus() != MediaLifecycleStatus.RESTORING) {
            return;
        }
        Long chapterId = media.getChapterId();
        String originalPath = extractOriginalHqPath(media.getHqPath());
        int targetPage = media.getOriginalPageNumber() != null
                ? media.getOriginalPageNumber() : media.getPageNumber();
        targetPage = firstFreePageNumber(chapterId, targetPage, mediaId);

        mediaMapper.markRestored(mediaId, StorageRootKeys.HQ, originalPath, targetPage);
        comicStatsService.refreshByChapter(chapterId);
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
    public void applyComicPurgeCompleted(Long comicId) {
        List<Chapter> chapters = chapterMapper.selectByComicIdOrderByGlobalOrder(comicId);
        List<Long> chapterIds = chapters.stream().map(Chapter::getId).toList();
        if (!chapterIds.isEmpty()) {
            mediaMapper.deleteByChapterIds(chapterIds);
        }
        trashDataMapper.deleteReadingHistoryByComicId(comicId);
        chapterMapper.deleteByComicId(comicId);
        catalogMapper.deleteByComicId(comicId);
        trashDataMapper.deleteComicTagsByComicId(comicId);

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
    public void applyChapterPurgeCompleted(Long chapterId) {
        mediaMapper.deleteByChapterId(chapterId);
        Chapter chapter = chapterMapper.selectById(chapterId);
        if (chapter != null && chapter.getStatus() == ChapterLifecycleStatus.PURGING) {
            chapter.setStatus(ChapterLifecycleStatus.DELETED);
            chapterMapper.updateById(chapter);
            catalogCacheInvalidator.evict(chapter.getComicId());
        }
        log.info("章节永久清理完成: chapterId={}", chapterId);
    }

    /** 媒体永久清理：删除媒体行。 */
    public void applyMediaPurgeCompleted(Long mediaId) {
        Media media = mediaMapper.selectById(mediaId);
        Long chapterId = media != null ? media.getChapterId() : null;
        mediaMapper.deleteById(mediaId);
        if (chapterId != null) {
            comicStatsService.refreshByChapter(chapterId);
            Chapter chapter = chapterMapper.selectById(chapterId);
            if (chapter != null) {
                catalogCacheInvalidator.evict(chapter.getComicId());
            }
        }
        log.info("媒体永久清理完成: mediaId={}", mediaId);
    }

    // ======================== Failed 回退 ========================

    /**
     * 回收失败：依据 actual.json 判断补偿结果。
     * COMPENSATED → 文件已全部回滚，实体回 READY；否则保持 TRASHING（可 RECONCILE/RETRY）。
     */
    public void applyTrashFailed(String targetType, Long targetId, Long taskId) {
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
    public void revertToTrashed(String targetType, Long targetId) {
        switch (targetType) {
            case "COMIC" -> {
                Comic comic = comicMapper.selectById(targetId);
                if (comic != null && (comic.getStatus() == ComicStatus.RESTORING
                        || comic.getStatus() == ComicStatus.PURGING)) {
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
    public void revertToReady(String targetType, Long targetId) {
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

    // ======================== 辅助 ========================

    /**
     * 从 TRASH 引用（media/{mediaId}/{taskId}/hq/{original}）还原原始 HQ 路径。
     */
    private static String extractOriginalHqPath(String trashRef) {
        if (trashRef == null) {
            return null;
        }
        int hqMarkerIndex = trashRef.indexOf(TRASH_HQ_MARKER);
        return hqMarkerIndex >= 0 ? trashRef.substring(hqMarkerIndex + TRASH_HQ_MARKER.length()) : null;
    }

    /**
     * 计算恢复页码：优先复用 originalPageNumber；被占用时取 1..N+1 的首个空位。
     */
    private int firstFreePageNumber(Long chapterId, int preferred, Long mediaId) {
        List<Media> existing = mediaMapper.selectPageNumbersByChapterId(chapterId);
        Set<Integer> occupied = new HashSet<>();
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
}
