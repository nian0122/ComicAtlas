package com.comicatlas.api.recovery.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.comicatlas.api.recovery.application.port.out.RecoveryTaskPersistencePort;
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
    public IPage<RecoveryTask> findPage(int page, int size) {
        return recoveryTaskMapper.selectPageOrderByCreatedAtDesc(new Page<>(page, size));
    }

    @Override
    public RecoveryTask findById(Long taskId) { return recoveryTaskMapper.selectById(taskId); }

    @Override
    public long countActiveTasks() { return recoveryTaskMapper.countActiveTasks(); }

    @Override
    public void insert(RecoveryTask task) { recoveryTaskMapper.insert(task); }

    @Override
    public void update(RecoveryTask task) { recoveryTaskMapper.updateById(task); }
}
