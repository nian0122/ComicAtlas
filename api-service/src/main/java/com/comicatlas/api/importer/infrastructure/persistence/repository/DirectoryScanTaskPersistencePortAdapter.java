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

    @Override public Long insert(DirectoryScanTaskPersistencePort.CreateCommand command) {
        DirectoryScanTask task = new DirectoryScanTask();
        task.setStatus(command.status()); task.setDirectoryPath(command.directoryPath());
        task.setTotalItems(command.totalItems()); task.setRetryCount(command.retryCount());
        directoryScanTaskMapper.insert(task);
        return task.getId();
    }
    @Override public DirectoryScanTaskPersistencePort.Snapshot findById(Long taskId) {
        return toSnapshot(directoryScanTaskMapper.selectById(taskId));
    }
    @Override public int update(DirectoryScanTaskPersistencePort.UpdateCommand command) {
        DirectoryScanTask task = new DirectoryScanTask();
        task.setId(command.id()); task.setManagementTaskId(command.managementTaskId());
        task.setStatus(command.status()); task.setDirectoryPath(command.directoryPath());
        task.setTotalItems(command.totalItems()); task.setResultJson(command.resultJson());
        task.setErrorMessage(command.errorMessage()); task.setRetryCount(command.retryCount());
        task.setStartedAt(command.startedAt()); task.setEndedAt(command.endedAt());
        return directoryScanTaskMapper.updateById(task);
    }

    private DirectoryScanTaskPersistencePort.Snapshot toSnapshot(DirectoryScanTask task) {
        return task == null ? null : new DirectoryScanTaskPersistencePort.Snapshot(task.getId(),
                task.getManagementTaskId(), task.getStatus(), task.getDirectoryPath(), task.getTotalItems(),
                task.getResultJson(), task.getErrorMessage(), task.getRetryCount(), task.getCreatedAt(),
                task.getStartedAt(), task.getEndedAt());
    }
}
