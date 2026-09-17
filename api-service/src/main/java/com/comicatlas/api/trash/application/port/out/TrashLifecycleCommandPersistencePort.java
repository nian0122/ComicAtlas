package com.comicatlas.api.trash.application.port.out;

import com.comicatlas.api.task.domain.model.TaskType;
import com.comicatlas.api.task.infrastructure.persistence.entity.ManagementTaskItem;
import com.comicatlas.contract.common.enums.ChapterLifecycleStatus;
import com.comicatlas.contract.common.enums.ComicStatus;
import com.comicatlas.contract.common.enums.MediaLifecycleStatus;

import java.util.List;
import java.time.LocalDateTime;

/** 回收生命周期应用服务访问状态变更与任务定位的输出端口。 */
public interface TrashLifecycleCommandPersistencePort {
    ComicSnapshot findComic(Long comicId);
    ChapterSnapshot findChapter(Long chapterId);
    List<ChapterSnapshot> findChapters(Long comicId);
    MediaSnapshot findMedia(Long mediaId);
    void updateComic(ComicUpdateCommand command);
    void updateChapter(ChapterUpdateCommand command);
    void updateMedia(MediaUpdateCommand command);
    List<MediaPageSnapshot> findPageNumbers(Long chapterId);
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

    record ComicSnapshot(Long id, ComicStatus status, LocalDateTime trashedAt, LocalDateTime deletedAt) { }

    record ChapterSnapshot(Long id, Long comicId, ChapterLifecycleStatus status,
                           LocalDateTime trashedAt) { }

    record MediaSnapshot(Long id, Long chapterId, MediaLifecycleStatus status, String hqPath,
                         Integer originalPageNumber, Integer pageNumber) { }

    record MediaPageSnapshot(Long id, Integer pageNumber) { }

    record ComicUpdateCommand(Long id, ComicStatus status, LocalDateTime trashedAt,
                              LocalDateTime deletedAt) { }

    record ChapterUpdateCommand(Long id, ChapterLifecycleStatus status, LocalDateTime trashedAt) { }

    record MediaUpdateCommand(Long id, MediaLifecycleStatus status, LocalDateTime trashedAt,
                              Integer pageNumber) { }
}
