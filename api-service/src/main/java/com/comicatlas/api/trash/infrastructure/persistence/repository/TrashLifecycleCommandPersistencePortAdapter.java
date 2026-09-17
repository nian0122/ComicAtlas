package com.comicatlas.api.trash.infrastructure.persistence.repository;

import com.comicatlas.api.task.domain.model.TaskType;
import com.comicatlas.api.task.infrastructure.persistence.entity.ManagementTaskItem;
import com.comicatlas.api.task.infrastructure.persistence.mapper.ManagementTaskItemMapper;
import com.comicatlas.api.trash.application.port.out.TrashLifecycleCommandPersistencePort;
import com.comicatlas.api.trash.infrastructure.persistence.mapper.TrashDataMapper;
import com.comicatlas.persistence.comic.entity.Chapter;
import com.comicatlas.persistence.comic.entity.Comic;
import com.comicatlas.persistence.comic.entity.Media;
import com.comicatlas.persistence.comic.mapper.ChapterMapper;
import com.comicatlas.persistence.comic.mapper.ComicMapper;
import com.comicatlas.persistence.comic.mapper.MediaMapper;
import com.comicatlas.persistence.comic.mapper.CatalogMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/** 回收生命周期命令端口的 MyBatis 实现。 */
@Component
@RequiredArgsConstructor
public class TrashLifecycleCommandPersistencePortAdapter implements TrashLifecycleCommandPersistencePort {
    private final ComicMapper comicMapper;
    private final ChapterMapper chapterMapper;
    private final MediaMapper mediaMapper;
    private final CatalogMapper catalogMapper;
    private final ManagementTaskItemMapper itemMapper;
    private final TrashDataMapper trashDataMapper;

    @Override public Comic findComic(Long comicId) { return comicMapper.selectById(comicId); }
    @Override public Chapter findChapter(Long chapterId) { return chapterMapper.selectById(chapterId); }
    @Override public List<Chapter> findChapters(Long comicId) {
        return chapterMapper.selectByComicIdOrderByGlobalOrder(comicId);
    }
    @Override public Media findMedia(Long mediaId) { return mediaMapper.selectById(mediaId); }
    @Override public void updateComic(Comic comic) { comicMapper.updateById(comic); }
    @Override public void updateChapter(Chapter chapter) { chapterMapper.updateById(chapter); }
    @Override public void updateMedia(Media media) { mediaMapper.updateById(media); }
    @Override public List<Media> findPageNumbers(Long chapterId) {
        return mediaMapper.selectPageNumbersByChapterId(chapterId);
    }
    @Override public int markMediaTrashed(Long mediaId, java.time.LocalDateTime trashedAt,
                                          String hqRoot, String hqPath) {
        return mediaMapper.markTrashed(mediaId, trashedAt, hqRoot, hqPath);
    }
    @Override public int markMediaRestored(Long mediaId, String hqRoot, String hqPath, int pageNumber) {
        return mediaMapper.markRestored(mediaId, hqRoot, hqPath, pageNumber);
    }
    @Override public int deleteMediaByChapterIds(List<Long> chapterIds) {
        return mediaMapper.deleteByChapterIds(chapterIds);
    }
    @Override public int deleteMediaByChapterId(Long chapterId) { return mediaMapper.deleteByChapterId(chapterId); }
    @Override public int deleteMedia(Long mediaId) { return mediaMapper.deleteById(mediaId); }
    @Override public int deleteChaptersByComicId(Long comicId) { return chapterMapper.deleteByComicId(comicId); }
    @Override public int deleteCatalogsByComicId(Long comicId) { return catalogMapper.deleteByComicId(comicId); }
    @Override public int bindTrashManifest(Long itemId, Long manifestTaskId) {
        return itemMapper.bindTrashManifest(itemId, manifestTaskId);
    }
    @Override public ManagementTaskItem findLatestTaskItem(String targetType, Long targetId, TaskType operationType) {
        return trashDataMapper.selectLatestTaskItem(targetType, targetId, operationType);
    }
    @Override public int deleteReadingHistory(Long comicId) {
        return trashDataMapper.deleteReadingHistoryByComicId(comicId);
    }
    @Override public int deleteComicTags(Long comicId) {
        return trashDataMapper.deleteComicTagsByComicId(comicId);
    }
}
