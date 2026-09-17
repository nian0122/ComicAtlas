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
    public ExportTask findTask(Long taskId) { return exportTaskMapper.selectById(taskId); }

    @Override
    public ExportTask findTaskByManagementTaskId(Long managementTaskId) {
        return exportTaskMapper.selectByManagementTaskId(managementTaskId);
    }

    @Override
    public ExportTask findActiveTask(Long comicId) { return exportTaskMapper.selectActiveByComicId(comicId); }

    @Override
    public List<ExportTask> findTasksByComicId(Long comicId) {
        return exportTaskMapper.selectByComicIdOrderByCreatedAtDesc(comicId);
    }

    @Override
    public List<ExportTask> findAllTasks() { return exportTaskMapper.selectAllOrderByCreatedAtDesc(); }

    @Override
    public void insertTask(ExportTask task) { exportTaskMapper.insert(task); }

    @Override
    public void updateTask(ExportTask task) { exportTaskMapper.updateById(task); }

    @Override
    public int resetTask(Long taskId, ExportTaskStatus pendingStatus) {
        return exportTaskMapper.resetForRetry(taskId, pendingStatus);
    }
}
