package com.comicatlas.api.management.dto;

import lombok.Data;

/**
 * 媒体操作命令提交结果。
 * <p>
 * taskId 非空表示已创建统一管理任务并返回其 ID；
 * taskId 为 null 表示无待处理目标（幂等跳过）。
 */
@Data
public class OperationSubmitResult {
    private Long taskId;
    private String taskType;
    private String status;
    private Integer itemCount;

    public static OperationSubmitResult of(Long taskId, String taskType, String status, Integer itemCount) {
        OperationSubmitResult r = new OperationSubmitResult();
        r.setTaskId(taskId);
        r.setTaskType(taskType);
        r.setStatus(status);
        r.setItemCount(itemCount);
        return r;
    }

    public boolean isNoOp() {
        return taskId == null;
    }
}
