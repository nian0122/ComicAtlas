package com.comicatlas.api.storage.infrastructure.persistence.repository;

import com.comicatlas.api.storage.application.port.out.ComicStatsPersistencePort;
import com.comicatlas.persistence.comic.entity.Chapter;
import com.comicatlas.persistence.comic.entity.Media;
import com.comicatlas.persistence.comic.mapper.ChapterMapper;
import com.comicatlas.persistence.comic.mapper.ComicMapper;
import com.comicatlas.persistence.comic.mapper.MediaMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/** 漫画统计持久化端口的 MyBatis 适配器。 */
@Component
@RequiredArgsConstructor
public class ComicStatsPersistencePortAdapter implements ComicStatsPersistencePort {
    private final ChapterMapper chapterMapper;
    private final ComicMapper comicMapper;
    private final MediaMapper mediaMapper;

    @Override public Long findChapterComicId(Long chapterId) {
        Chapter chapter = chapterMapper.selectById(chapterId);
        return chapter == null ? null : chapter.getComicId();
    }
    @Override public List<ChapterStatisticsSnapshot> findChaptersByComic(Long comicId) {
        return chapterMapper.selectByComicIdOrderByGlobalOrder(comicId).stream()
                .map(chapter -> new ChapterStatisticsSnapshot(chapter.getId(), chapter.getComicId(),
                        chapter.getPageCount()))
                .toList();
    }
    @Override public List<MediaStatisticsSnapshot> findMediaByChapterIds(List<Long> chapterIds) {
        return mediaMapper.selectByChapterIds(chapterIds).stream().map(ComicStatsPersistencePortAdapter::toSnapshot)
                .toList();
    }
    @Override public List<MediaStatisticsSnapshot> findVideosByChapterIds(List<Long> chapterIds) {
        return mediaMapper.selectVideosByChapterIds(chapterIds).stream()
                .map(ComicStatsPersistencePortAdapter::toSnapshot).toList();
    }
    @Override public long countActiveMediaByChapter(Long chapterId) {
        return mediaMapper.countActiveByChapterId(chapterId);
    }
    @Override public long countActiveMediaByChapters(List<Long> chapterIds) {
        return mediaMapper.countActiveByChapterIds(chapterIds);
    }
    @Override public void updateChapterPageCount(Long chapterId, int pageCount) {
        chapterMapper.updatePageCount(chapterId, pageCount);
    }
    @Override public void updateChapterPageCountBatch(List<ChapterStatisticsSnapshot> chapters) {
        chapterMapper.updatePageCountBatch(chapters.stream().map(snapshot -> {
            Chapter chapter = new Chapter();
            chapter.setId(snapshot.id());
            chapter.setPageCount(snapshot.pageCount());
            return chapter;
        }).toList());
    }
    @Override public void updateStorageStats(Long comicId, long hqSize, long lqSize) {
        comicMapper.updateStorageStats(comicId, hqSize, lqSize);
    }
    @Override public void updateTotalPages(Long comicId, int totalPages) {
        comicMapper.updateTotalPages(comicId, totalPages);
    }
    @Override public void updateAllStats(Long comicId, int totalPages, long hqSize, long lqSize) {
        comicMapper.updateAllStats(comicId, totalPages, hqSize, lqSize);
    }

    private static MediaStatisticsSnapshot toSnapshot(Media media) {
        return new MediaStatisticsSnapshot(media.getId(), media.getChapterId(), media.getMediaType(),
                media.getHqSize(), media.getLqSize(), media.getHqStatus(), media.getLqStatus(), media.getStatus());
    }
}
