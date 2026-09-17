package com.comicatlas.api.importer.application.port.out;

import com.comicatlas.api.importer.infrastructure.persistence.entity.DirectoryScanTask;

/** 目录扫描任务持久化输出端口。 */
public interface DirectoryScanTaskPersistencePort {
    void insert(DirectoryScanTask task);
    DirectoryScanTask findById(Long taskId);
    int update(DirectoryScanTask task);
}
