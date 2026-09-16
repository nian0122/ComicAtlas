package com.comicatlas.api.task.service;

import com.comicatlas.api.task.dto.CreateManagementTaskRequest;
import com.comicatlas.api.task.enums.TaskType;
import com.comicatlas.api.task.persistence.entity.ManagementTask;
import com.comicatlas.api.task.persistence.entity.ManagementTaskItem;

import java.util.List;

/**
 * 管理任务生命周期的业务扩展策略。
 *
 * <p>task 只负责调用时机和事务协调，具体业务通过此接口参与创建、取消和重试，
 * 避免通用任务服务依赖某个业务域的实现。</p>
 */
public interface TaskLifecyclePolicy {

    /** 在创建任务项前执行业务锁定。 */
    void lockOnCreate(TaskType taskType, List<CreateManagementTaskRequest.TaskTarget> targets);

    /** 取消任务后释放业务锁。 */
    void releaseAfterCancel(ManagementTask task, Long taskId,
                            long activeItems, List<ManagementTaskItem> items);

    /** 重试前准备业务状态。 */
    void prepareRetry(TaskType taskType, List<ManagementTaskItem> items);
}
