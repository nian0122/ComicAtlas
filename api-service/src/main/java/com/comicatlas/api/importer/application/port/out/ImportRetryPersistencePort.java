package com.comicatlas.api.importer.application.port.out;

import com.comicatlas.contract.common.enums.ComicStatus;

import java.util.List;

/** 导入重试协调所需的持久化输出端口。 */
public interface ImportRetryPersistencePort {
    int resetImportTask(ResetImportTaskCommand command);
    List<ChapterSnapshot> findChapters(Long comicId);
    ComicSnapshot findComic(Long comicId);
    int updateComic(ComicStatusUpdateCommand command);
    void deleteMediaByChapters(List<Long> chapterIds);
    void deleteChapters(Long comicId);
    void deleteCatalogs(Long comicId);

    record ChapterSnapshot(Long id, Integer globalOrder) {
    }

    record ComicSnapshot(Long id, ComicStatus status, Integer version) {
    }

    record ComicStatusUpdateCommand(Long id, ComicStatus status, Integer version) {
    }

    record ResetImportTaskCommand(Long id, int retryCount) {
    }
}
