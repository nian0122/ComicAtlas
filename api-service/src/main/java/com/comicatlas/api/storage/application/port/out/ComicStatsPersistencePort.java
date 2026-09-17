package com.comicatlas.api.storage.application.port.out;

import com.comicatlas.contract.common.enums.HqStatus;
import com.comicatlas.contract.common.enums.LqStatus;
import com.comicatlas.contract.common.enums.MediaLifecycleStatus;

import java.util.List;

/** 存储统计应用服务所需的持久化端口。 */
public interface ComicStatsPersistencePort {
    Long findChapterComicId(Long chapterId);
    List<ChapterStatisticsSnapshot> findChaptersByComic(Long comicId);
    List<MediaStatisticsSnapshot> findMediaByChapterIds(List<Long> chapterIds);
    List<MediaStatisticsSnapshot> findVideosByChapterIds(List<Long> chapterIds);
    long countActiveMediaByChapter(Long chapterId);
    long countActiveMediaByChapters(List<Long> chapterIds);
    void updateChapterPageCount(Long chapterId, int pageCount);
    void updateChapterPageCountBatch(List<ChapterStatisticsSnapshot> chapters);
    void updateStorageStats(Long comicId, long hqSize, long lqSize);
    void updateTotalPages(Long comicId, int totalPages);
    void updateAllStats(Long comicId, int totalPages, long hqSize, long lqSize);

    record ChapterStatisticsSnapshot(Long id, Long comicId, Integer pageCount) {
    }

    record MediaStatisticsSnapshot(Long id, Long chapterId, String mediaType, Long hqSize, Long lqSize,
                                   HqStatus hqStatus, LqStatus lqStatus, MediaLifecycleStatus status) {
    }
}
