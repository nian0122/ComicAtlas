package com.comicatlas.api.importer.infrastructure.persistence.repository;

import com.comicatlas.api.importer.application.port.out.DirectoryScanTaskPersistencePort;
import com.comicatlas.api.importer.infrastructure.persistence.entity.DirectoryScanTask;
import com.comicatlas.api.importer.infrastructure.persistence.mapper.DirectoryScanTaskMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 目录扫描任务持久化端口的 MyBatis 适配器。 */
@Component
@RequiredArgsConstructor
public class DirectoryScanTaskPersistencePortAdapter implements DirectoryScanTaskPersistencePort {
    private final DirectoryScanTaskMapper directoryScanTaskMapper;

    @Override public void insert(DirectoryScanTask task) { directoryScanTaskMapper.insert(task); }
    @Override public DirectoryScanTask findById(Long taskId) { return directoryScanTaskMapper.selectById(taskId); }
    @Override public int update(DirectoryScanTask task) { return directoryScanTaskMapper.updateById(task); }
}
