package com.comicatlas.api.task.application.port.out;

import com.comicatlas.api.task.domain.model.TaskType;

import java.util.List;

/**
 * 管理任务生命周期的业务扩展策略。
 *
 * <p>task 只负责调用时机和事务协调，具体业务通过此接口参与创建、取消和重试，
 * 避免通用任务服务依赖某个业务域的实现。</p>
 */
public interface TaskLifecyclePolicy {

    /** 在创建任务项前执行业务锁定。 */
    void lockOnCreate(TaskType taskType, List<TaskTarget> targets);

    /** 取消任务后释放业务锁。 */
    void releaseAfterCancel(TaskType taskType, long activeItems, List<TaskItemReference> items);

    /** 重试前准备业务状态。 */
    void prepareRetry(TaskType taskType, List<TaskItemReference> items);

    record TaskTarget(String targetType, Long targetId, TaskType operationType) {
    }

    record TaskItemReference(String targetType, Long targetId, TaskType operationType,
                             com.comicatlas.api.task.domain.model.ManagementTaskStatus status) {
    }
}
