package com.comicatlas.api.importer.service;

import com.comicatlas.api.importer.persistence.entity.ImportTask;
import com.comicatlas.persistence.comic.entity.Chapter;
import java.util.List;

/** 导入重试存储准备服务契约。 */
public interface ImportRetryStorageService {
    void restoreFinalizedToStaging(Long taskId, Long comicId, List<Chapter> chapters);
    void rebuildManifest(ImportTask task, Long comicId);
}
