package com.comicatlas.api.recovery.enums;

/** 存储恢复任务状态。终态：SUCCEEDED / FAILED / CANCELLED。 */
public enum RecoveryTaskStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED
}
