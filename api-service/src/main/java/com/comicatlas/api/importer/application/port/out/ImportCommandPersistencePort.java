package com.comicatlas.api.importer.application.port.out;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.comicatlas.api.importer.domain.model.ImportTaskStatus;
import com.comicatlas.contract.common.enums.ComicStatus;
import com.comicatlas.contract.common.enums.SourceType;
import java.time.LocalDateTime;

/** 导入任务创建、查询和取消所需的持久化输出端口。 */
public interface ImportCommandPersistencePort {
    ImportTaskSnapshot findImportTask(Long taskId);
    ImportTaskSnapshot findByManagementTaskId(Long managementTaskId);
    IPage<ImportTaskSnapshot> findPage(int page, int size, ImportTaskStatus status, String batchId);
    Long insertImportTask(CreateTaskCommand command);
    int updateImportTask(UpdateTaskCommand command);
    ComicSnapshot findComicBySourceGallery(String sourceType, String galleryId);
    ComicSnapshot insertComic(ComicCreateCommand command);

    record ComicSnapshot(Long id, ComicStatus status, Integer version) {
    }

    record ComicCreateCommand(SourceType sourceType, ComicStatus status, String title,
                              String sourceGalleryId, String sourceGalleryToken, String sourceRef) {
    }

    record ImportTaskSnapshot(Long id, Long managementTaskId, Long comicId, String sourceRef,
                              SourceType sourceType, String sourcePath, String batchId,
                              ImportTaskStatus status, Integer progress, Integer totalPages,
                              Integer downloadedPages, String downloadMethod, Long downloadSpeed,
                              Integer etaSeconds, String errorMessage, Integer retryCount,
                              LocalDateTime startTime, LocalDateTime endTime, Long durationMs,
                              LocalDateTime createdAt) {
    }

    record CreateTaskCommand(Long comicId, String sourceRef, SourceType sourceType, String sourcePath,
                             String batchId, ImportTaskStatus status) {
    }

    record UpdateTaskCommand(Long id, Long managementTaskId, ImportTaskStatus status, Integer progress,
                             String errorMessage, Integer retryCount) {
    }
}
