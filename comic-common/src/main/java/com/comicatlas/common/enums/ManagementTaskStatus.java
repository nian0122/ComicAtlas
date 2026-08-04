package com.comicatlas.common.enums;

/**
 * 管理任务状态 — 用于导入/恢复/导出/扫描等异步管理任务。
 * <p>
 * 终态：CANCELLED、SUCCEEDED、PARTIALLY_SUCCEEDED、FAILED。
 * 导入阶段用 TaskStage，不写入此状态。
 */
public enum ManagementTaskStatus {
    /** 排队等待执行 */
    QUEUED,
    /** 运行中 */
    RUNNING,
    /** 取消请求已发送，等待 Worker 确认 */
    CANCELLING,
    /** 已取消 */
    CANCELLED,
    /** 全部成功 */
    SUCCEEDED,
    /** 部分成功（如批量导入有失败项） */
    PARTIALLY_SUCCEEDED,
    /** 全部失败 */
    FAILED;

    public boolean isTerminal() {
        return this == CANCELLED || this == SUCCEEDED || this == PARTIALLY_SUCCEEDED || this == FAILED;
    }

    public boolean isProcessing() {
        return this == QUEUED || this == RUNNING || this == CANCELLING;
    }
}
