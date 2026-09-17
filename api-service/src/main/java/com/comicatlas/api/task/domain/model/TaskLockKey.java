package com.comicatlas.api.task.domain.model;

/** 管理任务项的业务锁键生成规则。 */
public final class TaskLockKey {
    private TaskLockKey() {
    }

    public static String of(String targetType, Long targetId, TaskType operationType) {
        return targetType + ":" + targetId + ":" + operationType.name();
    }
}
