package com.comicatlas.api.importer.application.port.in;

import com.comicatlas.api.importer.infrastructure.persistence.entity.ImportTask;
import com.comicatlas.api.importer.application.port.out.ImportRetryPersistencePort;
import java.util.List;

/** 导入重试存储准备服务契约。 */
public interface ImportRetryStorageService {
    void restoreFinalizedToStaging(Long taskId, Long comicId,
                                   List<ImportRetryPersistencePort.ChapterSnapshot> chapters);
    void rebuildManifest(ImportTask task, Long comicId);
}
