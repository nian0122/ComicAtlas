package com.comicatlas.api.importer.application.port.out;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.comicatlas.api.importer.domain.model.ImportTaskStatus;
import com.comicatlas.api.importer.infrastructure.persistence.entity.ImportTask;
import com.comicatlas.contract.common.enums.ComicStatus;
import com.comicatlas.contract.common.enums.SourceType;

/** 导入任务创建、查询和取消所需的持久化输出端口。 */
public interface ImportCommandPersistencePort {
    ImportTask findImportTask(Long taskId);
    ImportTask findByManagementTaskId(Long managementTaskId);
    IPage<ImportTask> findPage(Page<ImportTask> page, ImportTaskStatus status, String batchId);
    void insertImportTask(ImportTask task);
    int updateImportTask(ImportTask task);
    ComicSnapshot findComicBySourceGallery(String sourceType, String galleryId);
    ComicSnapshot insertComic(ComicCreateCommand command);

    record ComicSnapshot(Long id, ComicStatus status, Integer version) {
    }

    record ComicCreateCommand(SourceType sourceType, ComicStatus status, String title,
                              String sourceGalleryId, String sourceGalleryToken, String sourceRef) {
    }
}
