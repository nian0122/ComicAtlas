package com.comicatlas.api.recovery.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.comicatlas.api.recovery.application.port.out.RecoveryTaskPersistencePort;
import com.comicatlas.api.recovery.application.port.out.RecoveryTaskPersistencePort.CreateCommand;
import com.comicatlas.api.recovery.application.port.out.RecoveryTaskPersistencePort.RecoveryTaskSnapshot;
import com.comicatlas.api.recovery.application.port.out.RecoveryTaskPersistencePort.UpdateCommand;
import com.comicatlas.api.recovery.infrastructure.persistence.entity.RecoveryTask;
import com.comicatlas.api.recovery.infrastructure.persistence.mapper.RecoveryTaskMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 恢复任务持久化端口的 MyBatis 实现。 */
@Component
@RequiredArgsConstructor
public class RecoveryTaskPersistencePortAdapter implements RecoveryTaskPersistencePort {

    private final RecoveryTaskMapper recoveryTaskMapper;

    @Override
    public IPage<RecoveryTaskSnapshot> findPage(int page, int size) {
        IPage<RecoveryTask> sourcePage = recoveryTaskMapper.selectPageOrderByCreatedAtDesc(new Page<>(page, size));
        IPage<RecoveryTaskSnapshot> targetPage = new Page<>(page, size);
        targetPage.setTotal(sourcePage.getTotal());
        targetPage.setRecords(sourcePage.getRecords().stream().map(this::toSnapshot).toList());
        return targetPage;
    }

    @Override
    public RecoveryTaskSnapshot findById(Long taskId) {
        RecoveryTask task = recoveryTaskMapper.selectById(taskId);
        return task == null ? null : toSnapshot(task);
    }

    @Override
    public long countActiveTasks() { return recoveryTaskMapper.countActiveTasks(); }

    @Override
    public Long insert(CreateCommand command) {
        RecoveryTask task = new RecoveryTask();
        task.setStatus(command.status());
        task.setTotalComics(command.totalComics());
        task.setRecoveredComics(command.recoveredComics());
        task.setSkippedComics(command.skippedComics());
        task.setPlaceholderComics(command.placeholderComics());
        task.setErrorComics(command.errorComics());
        task.setRetryCount(command.retryCount());
        recoveryTaskMapper.insert(task);
        return task.getId();
    }

    @Override
    public void update(UpdateCommand command) {
        RecoveryTask task = new RecoveryTask();
        task.setId(command.id());
        task.setManagementTaskId(command.managementTaskId());
        task.setStatus(command.status());
        task.setTotalComics(command.totalComics());
        task.setRecoveredComics(command.recoveredComics());
        task.setSkippedComics(command.skippedComics());
        task.setPlaceholderComics(command.placeholderComics());
        task.setErrorComics(command.errorComics());
        task.setErrorMessage(command.errorMessage());
        task.setErrorDetails(command.errorDetails());
        task.setRetryCount(command.retryCount());
        task.setStartedAt(command.startedAt());
        task.setEndedAt(command.endedAt());
        recoveryTaskMapper.updateById(task);
    }

    private RecoveryTaskSnapshot toSnapshot(RecoveryTask task) {
        return new RecoveryTaskSnapshot(task.getId(), task.getManagementTaskId(), task.getStatus(),
                task.getTotalComics(), task.getRecoveredComics(), task.getSkippedComics(),
                task.getPlaceholderComics(), task.getErrorComics(), task.getErrorMessage(), task.getErrorDetails(),
                task.getRetryCount(), task.getCreatedAt(), task.getStartedAt(), task.getEndedAt());
    }
}
