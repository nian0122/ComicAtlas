package com.comicatlas.api.common.enums;

/**
 * 导入任务进度状态（Worker 实时推送）。
 * <p>
 * 终态：SUCCESS、FAILED、CANCELLED。
 * 导入阶段用 TaskStage 细分，不写入 task 实体的 status 列。
 */
public enum ImportTaskStatus {
    PENDING,
    PARSING,
    IMPORTING,
    SUCCESS,
    FAILED,
    CANCELLED;

    public boolean isTerminal() { return this == SUCCESS || this == FAILED || this == CANCELLED; }
}
