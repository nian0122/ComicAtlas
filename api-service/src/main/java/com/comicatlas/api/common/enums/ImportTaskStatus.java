package com.comicatlas.api.common.enums;

/**
 * 导入任务进度状态（Worker 实时推送）。
 * <p>
 * 终态：SUCCESS、FAILED、CANCELLED。
 * FINALIZING：全部章节存储最终化已完成（media 全 READY），等待磁盘 metadata.json 重建成功
 * 结果事件后才能置 SUCCESS——作为防重标记，防止乱序/重投的 finalize.completed 重复触发收尾。
 * 导入阶段用 TaskStage 细分，不写入 task 实体的 status 列。
 */
public enum ImportTaskStatus {
    PENDING,
    PARSING,
    IMPORTING,
    FINALIZING,
    SUCCESS,
    FAILED,
    CANCELLED;

    public boolean isTerminal() { return this == SUCCESS || this == FAILED || this == CANCELLED; }
}
