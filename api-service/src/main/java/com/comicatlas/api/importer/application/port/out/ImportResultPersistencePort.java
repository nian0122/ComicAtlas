package com.comicatlas.api.importer.application.port.out;

import com.comicatlas.api.importer.infrastructure.persistence.entity.ImportTask;
import com.comicatlas.contract.common.enums.ComicStatus;

/** 导入结果状态处理所需的持久化端口。 */
public interface ImportResultPersistencePort {
    ImportTask findImportTask(Long taskId);
    void updateImportTask(ImportTask task);
    ComicSnapshot findComic(Long comicId);
    int updateComic(ComicStatusUpdateCommand command);

    record ComicSnapshot(Long id, ComicStatus status, Integer version) {
    }

    record ComicStatusUpdateCommand(Long id, ComicStatus status, Integer version) {
    }
}
