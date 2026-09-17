package com.comicatlas.api.trash.application.port.out;

import com.comicatlas.api.task.domain.model.TaskType;
import com.comicatlas.api.task.infrastructure.persistence.entity.ManagementTaskItem;
import com.comicatlas.persistence.comic.entity.Chapter;
import com.comicatlas.persistence.comic.entity.Comic;
import com.comicatlas.persistence.comic.entity.Media;

import java.util.List;
import java.time.LocalDateTime;

/** 回收生命周期应用服务访问状态变更与任务定位的输出端口。 */
public interface TrashLifecycleCommandPersistencePort {
    Comic findComic(Long comicId);
    Chapter findChapter(Long chapterId);
    List<Chapter> findChapters(Long comicId);
    Media findMedia(Long mediaId);
    void updateComic(Comic comic);
    void updateChapter(Chapter chapter);
    void updateMedia(Media media);
    List<Media> findPageNumbers(Long chapterId);
    int markMediaTrashed(Long mediaId, LocalDateTime trashedAt, String hqRoot, String hqPath);
    int markMediaRestored(Long mediaId, String hqRoot, String hqPath, int pageNumber);
    int deleteMediaByChapterIds(List<Long> chapterIds);
    int deleteMediaByChapterId(Long chapterId);
    int deleteMedia(Long mediaId);
    int deleteChaptersByComicId(Long comicId);
    int deleteCatalogsByComicId(Long comicId);
    int bindTrashManifest(Long itemId, Long manifestTaskId);
    ManagementTaskItem findLatestTaskItem(String targetType, Long targetId, TaskType operationType);
    int deleteReadingHistory(Long comicId);
    int deleteComicTags(Long comicId);
}
