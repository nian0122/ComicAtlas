package com.comicatlas.api.importer.application.port.out;

import com.comicatlas.api.importer.infrastructure.persistence.entity.ImportTask;
import com.comicatlas.contract.common.enums.ChapterLifecycleStatus;
import com.comicatlas.contract.common.enums.ComicStatus;
import java.util.List;

/** 导入存储最终化所需的持久化端口。 */
public interface ImportFinalizationPersistencePort {
    ImportTask findImportTask(Long taskId);
    ComicSnapshot findComicForUpdate(Long comicId);
    void updateImportTask(ImportTask task);
    List<ChapterSnapshot> findChapters(Long comicId);
    ChapterSnapshot findChapter(Long chapterId);
    void updateChapter(ChapterStatusUpdateCommand command);
    void markMediaFinalized(Long chapterId, String relativePath);
    long countPendingMedia(List<Long> chapterIds, String hqStatus);
    List<MediaSnapshot> findAllMedia(List<Long> chapterIds);
    void updateComic(ComicStatusUpdateCommand command);

    record ComicSnapshot(Long id, ComicStatus status, Integer version) {
    }

    record ChapterSnapshot(Long id, Long comicId, ChapterLifecycleStatus status) {
    }

    record MediaSnapshot(Long id, Long hqSize) {
    }

    record ChapterStatusUpdateCommand(Long id, ChapterLifecycleStatus status) {
    }

    record ComicStatusUpdateCommand(Long id, ComicStatus status, Integer totalPages,
                                    Long hqSize, Integer version) {
    }
}
