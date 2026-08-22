package com.comicatlas.api.task.dto;

import lombok.Data;

/**
 * 媒体操作命令提交结果。
 * <p>
 * taskId 非空表示已创建统一管理任务并返回其 ID；
 * taskId 为 null 表示无待处理目标（幂等跳过）。
 */
@Data
public class OperationSubmitResultDTO {
    private Long taskId;
    private String taskType;
    private String status;
    private Integer itemCount;

    public static OperationSubmitResultDTO of(Long taskId, String taskType, String status, Integer itemCount) {
        OperationSubmitResultDTO r = new OperationSubmitResultDTO();
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
