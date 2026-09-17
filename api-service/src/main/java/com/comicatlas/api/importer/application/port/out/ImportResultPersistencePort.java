package com.comicatlas.api.importer.application.port.out;

import com.comicatlas.api.importer.domain.model.ImportTaskStatus;
import java.time.LocalDateTime;
import com.comicatlas.contract.common.enums.ComicStatus;

/** 导入结果状态处理所需的持久化端口。 */
public interface ImportResultPersistencePort {
    ImportTaskSnapshot findImportTask(Long taskId);
    void updateImportTask(ImportTaskUpdateCommand command);
    ComicSnapshot findComic(Long comicId);
    int updateComic(ComicStatusUpdateCommand command);

    record ComicSnapshot(Long id, ComicStatus status, Integer version) {
    }

    record ComicStatusUpdateCommand(Long id, ComicStatus status, Integer version) {
    }

    record ImportTaskSnapshot(Long id, Long comicId, Long managementTaskId, ImportTaskStatus status,
                              LocalDateTime startTime, String errorMessage) {
    }

    record ImportTaskUpdateCommand(Long id, Long comicId, Long managementTaskId, ImportTaskStatus status,
                                   Integer progress, Long downloadSpeed, Integer etaSeconds,
                                   String downloadMethod, String errorMessage, LocalDateTime startTime,
                                   LocalDateTime endTime) {
    }
}
