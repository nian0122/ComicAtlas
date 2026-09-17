package com.comicatlas.api.exporter.application.port.out;

import com.comicatlas.api.exporter.domain.model.ExportTaskStatus;
import com.comicatlas.api.exporter.infrastructure.persistence.entity.ExportTask;

import java.util.List;

/** 导出上下文访问漫画与导出任务持久化能力的输出端口。 */
public interface ExportPersistencePort {

    ComicSnapshot findComic(Long comicId);

    ExportTask findTask(Long taskId);

    ExportTask findTaskByManagementTaskId(Long managementTaskId);

    ExportTask findActiveTask(Long comicId);

    List<ExportTask> findTasksByComicId(Long comicId);

    List<ExportTask> findAllTasks();

    void insertTask(ExportTask task);

    void updateTask(ExportTask task);

    int resetTask(Long taskId, ExportTaskStatus pendingStatus);

    record ComicSnapshot(Long id, com.comicatlas.contract.common.enums.ComicStatus status) {
    }
}
