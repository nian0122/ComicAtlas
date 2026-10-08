package com.comicatlas.api.importer.service;

import com.comicatlas.common.event.ImportStorageFinalizeCompletedEvent;
import com.comicatlas.common.event.ImportStorageFinalizeFailedEvent;

/** 导入存储最终化结果服务契约。 */
public interface ImportFinalizationService {
    void applyCompleted(ImportStorageFinalizeCompletedEvent event);
    void applyFailed(ImportStorageFinalizeFailedEvent event);
}
