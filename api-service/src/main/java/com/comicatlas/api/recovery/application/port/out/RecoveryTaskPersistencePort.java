package com.comicatlas.api.recovery.application.port.out;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.comicatlas.api.recovery.infrastructure.persistence.entity.RecoveryTask;

/** 恢复任务应用服务访问持久化层的输出端口。 */
public interface RecoveryTaskPersistencePort {

    IPage<RecoveryTask> findPage(int page, int size);

    RecoveryTask findById(Long taskId);

    long countActiveTasks();

    void insert(RecoveryTask task);

    void update(RecoveryTask task);
}
