package com.comicatlas.api.exporter.application.port.out;

import com.comicatlas.api.exporter.domain.model.ExportTaskStatus;
import java.time.LocalDateTime;

import java.util.List;

/** 导出上下文访问漫画与导出任务持久化能力的输出端口。 */
public interface ExportPersistencePort {

    ComicSnapshot findComic(Long comicId);

    ExportTaskSnapshot findTask(Long taskId);

    ExportTaskSnapshot findTaskByManagementTaskId(Long managementTaskId);

    ExportTaskSnapshot findActiveTask(Long comicId);

    List<ExportTaskSnapshot> findTasksByComicId(Long comicId);

    List<ExportTaskSnapshot> findAllTasks();

    Long insertTask(CreateTaskCommand command);

    void updateTask(UpdateTaskCommand command);

    int resetTask(Long taskId, ExportTaskStatus pendingStatus);

    record ComicSnapshot(Long id, com.comicatlas.contract.common.enums.ComicStatus status) {
    }

    record ExportTaskSnapshot(Long id, Long managementTaskId, Long comicId, String format,
                              ExportTaskStatus status, Integer progress, String outputRoot,
                              String outputPath, Long outputSize, String errorMsg,
                              LocalDateTime createdAt, LocalDateTime completedAt) {
    }

    record CreateTaskCommand(Long comicId, String format, ExportTaskStatus status, Integer progress) {
    }

    record UpdateTaskCommand(Long id, Long managementTaskId, ExportTaskStatus status, Integer progress,
                             String outputRoot, String outputPath, Long outputSize, String errorMsg,
                             LocalDateTime completedAt) {
    }
}
