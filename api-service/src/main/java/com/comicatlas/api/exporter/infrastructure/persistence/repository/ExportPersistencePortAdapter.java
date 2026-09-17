package com.comicatlas.api.exporter.infrastructure.persistence.repository;

import com.comicatlas.api.exporter.application.port.out.ExportPersistencePort;
import com.comicatlas.api.exporter.domain.model.ExportTaskStatus;
import com.comicatlas.api.exporter.infrastructure.persistence.entity.ExportTask;
import com.comicatlas.api.exporter.infrastructure.persistence.mapper.ExportTaskMapper;
import com.comicatlas.persistence.comic.mapper.ComicMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/** 导出持久化端口的 MyBatis 实现。 */
@Component
@RequiredArgsConstructor
public class ExportPersistencePortAdapter implements ExportPersistencePort {

    private final ComicMapper comicMapper;
    private final ExportTaskMapper exportTaskMapper;

    @Override
    public ComicSnapshot findComic(Long comicId) {
        com.comicatlas.persistence.comic.entity.Comic comic = comicMapper.selectById(comicId);
        return comic == null ? null : new ComicSnapshot(comic.getId(), comic.getStatus());
    }

    @Override
    public ExportTaskSnapshot findTask(Long taskId) { return toSnapshot(exportTaskMapper.selectById(taskId)); }

    @Override
    public ExportTaskSnapshot findTaskByManagementTaskId(Long managementTaskId) {
        return toSnapshot(exportTaskMapper.selectByManagementTaskId(managementTaskId));
    }

    @Override
    public ExportTaskSnapshot findActiveTask(Long comicId) {
        return toSnapshot(exportTaskMapper.selectActiveByComicId(comicId));
    }

    @Override
    public List<ExportTaskSnapshot> findTasksByComicId(Long comicId) {
        return exportTaskMapper.selectByComicIdOrderByCreatedAtDesc(comicId).stream().map(this::toSnapshot).toList();
    }

    @Override
    public List<ExportTaskSnapshot> findAllTasks() {
        return exportTaskMapper.selectAllOrderByCreatedAtDesc().stream().map(this::toSnapshot).toList();
    }

    @Override
    public Long insertTask(ExportPersistencePort.CreateTaskCommand command) {
        ExportTask task = new ExportTask();
        task.setComicId(command.comicId());
        task.setFormat(command.format());
        task.setStatus(command.status());
        task.setProgress(command.progress());
        exportTaskMapper.insert(task);
        return task.getId();
    }

    @Override
    public void updateTask(ExportPersistencePort.UpdateTaskCommand command) {
        ExportTask task = new ExportTask();
        task.setId(command.id());
        task.setManagementTaskId(command.managementTaskId());
        task.setStatus(command.status());
        task.setProgress(command.progress());
        task.setOutputRoot(command.outputRoot());
        task.setOutputPath(command.outputPath());
        task.setOutputSize(command.outputSize());
        task.setErrorMsg(command.errorMsg());
        task.setCompletedAt(command.completedAt());
        exportTaskMapper.updateById(task);
    }

    @Override
    public int resetTask(Long taskId, ExportTaskStatus pendingStatus) {
        return exportTaskMapper.resetForRetry(taskId, pendingStatus);
    }

    private ExportTaskSnapshot toSnapshot(ExportTask task) {
        return task == null ? null : new ExportTaskSnapshot(task.getId(), task.getManagementTaskId(), task.getComicId(),
                task.getFormat(), task.getStatus(), task.getProgress(), task.getOutputRoot(), task.getOutputPath(),
                task.getOutputSize(), task.getErrorMsg(), task.getCreatedAt(), task.getCompletedAt());
    }
}
