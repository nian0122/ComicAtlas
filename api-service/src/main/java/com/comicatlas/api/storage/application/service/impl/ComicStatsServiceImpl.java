package com.comicatlas.api.storage.application.service.impl;

import com.comicatlas.contract.common.enums.HqStatus;
import com.comicatlas.contract.common.enums.LqStatus;
import com.comicatlas.contract.common.enums.MediaLifecycleStatus;
import com.comicatlas.api.storage.application.port.in.ComicStatsService;
import com.comicatlas.api.storage.application.port.out.ComicStatsPersistencePort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 漫画统计聚合服务（派生数据单一收口）。
 * <p>
 * 任何存储操作（LQ 生成 / HQ 删除 / 转码 / 回收 / 恢复 / 清理 / 上传）完成后
 * 统一经此类从实际 media/chapter 行重算整本统计（hqSize + lqSize + totalPages +
 * 各章 pageCount），避免业务落库逻辑各自实现聚合。存储管理页面的动态 SUM 聚合
 * （StorageMapper.xml）仍作为读取侧独立口径，本服务的缓存口径与其语义保持一致
 * （hqSize 统计 HQ 非 DELETED 的行，lqSize 统计 IMAGE 且 LQ READY 的行）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ComicStatsServiceImpl implements ComicStatsService {
    // 漫画统计契约由应用服务公开，具体实现保持在存储业务包内。

    private static final String MEDIA_TYPE_IMAGE = "IMAGE";
    private static final String MEDIA_TYPE_VIDEO = "VIDEO";
    private static final int UPDATE_BATCH_SIZE = 500;

    private final ComicStatsPersistencePort persistencePort;

    /** 单章变更后刷新：章节页数 + 整本统计（hqSize + lqSize + totalPages）。 */
    public void refreshByChapter(Long chapterId) {
        if (chapterId == null) {
            return;
        }
        Long comicId = persistencePort.findChapterComicId(chapterId);
        if (comicId == null) {
            return;
        }
        long pageCount = persistencePort.countActiveMediaByChapter(chapterId);
        persistencePort.updateChapterPageCount(chapterId, (int) pageCount);
        recomputeComicStats(comicId);
        refreshTotalPages(comicId);
    }

    /** 整本一次性刷新：一次预取媒体，批量更新各章节页数与整本统计。 */
    public void refreshByComic(Long comicId) {
        if (comicId == null) {
            return;
        }
        List<ComicStatsPersistencePort.ChapterStatisticsSnapshot> chapters = persistencePort.findChaptersByComic(comicId);
        if (chapters.isEmpty()) {
            updateComicStats(comicId, 0, 0L, 0L);
            return;
        }
        List<Long> chapterIds = chapters.stream()
                .map(ComicStatsPersistencePort.ChapterStatisticsSnapshot::id).toList();
        List<ComicStatsPersistencePort.MediaStatisticsSnapshot> mediaItems = persistencePort.findMediaByChapterIds(chapterIds);
        Map<Long, Long> pageCountByChapter = mediaItems.stream()
                .filter(media -> media.status() != MediaLifecycleStatus.DELETED
                        && media.status() != MediaLifecycleStatus.TRASHED)
                .collect(Collectors.groupingBy(ComicStatsPersistencePort.MediaStatisticsSnapshot::chapterId,
                        Collectors.counting()));
        List<ComicStatsPersistencePort.ChapterStatisticsSnapshot> updatedChapters = chapters.stream()
                .map(chapter -> new ComicStatsPersistencePort.ChapterStatisticsSnapshot(chapter.id(), chapter.comicId(),
                        Math.toIntExact(pageCountByChapter.getOrDefault(chapter.id(), 0L))))
                .toList();
        for (List<ComicStatsPersistencePort.ChapterStatisticsSnapshot> batch : partition(updatedChapters, UPDATE_BATCH_SIZE)) {
            persistencePort.updateChapterPageCountBatch(batch);
        }
        int totalPages = Math.toIntExact(pageCountByChapter.values().stream().mapToLong(Long::longValue).sum());
        long hqSize = calculateHqSize(mediaItems);
        long lqSize = calculateLqSize(mediaItems);
        updateComicStats(comicId, totalPages, hqSize, lqSize);
        log.debug("批量重算 comic 统计: comicId={}, chapters={}, hqSize={}, lqSize={}",
                comicId, chapters.size(), hqSize, lqSize);
    }

    /** 漫画 ID → 章节 ID 列表（批量操作创建的 COMIC 目标 item 展开处理）。 */
    public List<Long> chapterIdsOf(Long comicId) {
        return persistencePort.findChaptersByComic(comicId)
                .stream()
                .map(ComicStatsPersistencePort.ChapterStatisticsSnapshot::id)
                .toList();
    }

    /** 漫画 ID → 视频媒体 ID 列表（COMIC 目标转码 item 展开到视频页处理）。 */
    public List<Long> mediaIdsOf(Long comicId) {
        List<Long> chapterIds = chapterIdsOf(comicId);
        if (chapterIds.isEmpty()) {
            return List.of();
        }
        return persistencePort.findVideosByChapterIds(chapterIds)
                .stream()
                .map(ComicStatsPersistencePort.MediaStatisticsSnapshot::id)
                .toList();
    }

    /** 整本媒体行重算：hqSize（HQ 非 DELETED 的 fileSize 之和）+ lqSize（IMAGE 且 LQ READY 的 lqSize 之和）。 */
    private void recomputeComicStats(Long comicId) {
        List<ComicStatsPersistencePort.ChapterStatisticsSnapshot> chapters = persistencePort.findChaptersByComic(comicId);
        if (chapters.isEmpty()) {
            return;
        }
        List<Long> chapterIds = chapters.stream()
                .map(ComicStatsPersistencePort.ChapterStatisticsSnapshot::id).toList();
        List<ComicStatsPersistencePort.MediaStatisticsSnapshot> mediaItems = persistencePort.findMediaByChapterIds(chapterIds);
        long hqSize = calculateHqSize(mediaItems);
        long lqSize = calculateLqSize(mediaItems);
        persistencePort.updateStorageStats(comicId, hqSize, lqSize);
        log.debug("重算 comic 统计: comicId={}, hqSize={}, lqSize={}", comicId, hqSize, lqSize);
    }

    /** 整本总页数（非 DELETED/TRASHED 的媒体行数）。 */
    private void refreshTotalPages(Long comicId) {
        List<Long> chapterIds = chapterIdsOf(comicId);
        if (chapterIds.isEmpty()) {
            return;
        }
        long totalPages = persistencePort.countActiveMediaByChapters(chapterIds);
        persistencePort.updateTotalPages(comicId, (int) totalPages);
    }

    private long calculateHqSize(List<ComicStatsPersistencePort.MediaStatisticsSnapshot> mediaItems) {
        return mediaItems.stream()
                .filter(media -> media.hqStatus() != HqStatus.DELETED)
                .mapToLong(media -> media.hqSize() != null ? media.hqSize() : 0L)
                .sum();
    }

    private long calculateLqSize(List<ComicStatsPersistencePort.MediaStatisticsSnapshot> mediaItems) {
        return mediaItems.stream()
                .filter(media -> MEDIA_TYPE_IMAGE.equals(media.mediaType())
                        && media.lqStatus() == LqStatus.READY)
                .mapToLong(media -> media.lqSize() != null ? media.lqSize() : 0L)
                .sum();
    }

    private void updateComicStats(Long comicId, int totalPages, long hqSize, long lqSize) {
        persistencePort.updateAllStats(comicId, totalPages, hqSize, lqSize);
    }

    private static <T> List<List<T>> partition(List<T> source, int batchSize) {
        List<List<T>> batches = new ArrayList<>((source.size() + batchSize - 1) / batchSize);
        for (int index = 0; index < source.size(); index += batchSize) {
            batches.add(source.subList(index, Math.min(index + batchSize, source.size())));
        }
        return batches;
    }
}
