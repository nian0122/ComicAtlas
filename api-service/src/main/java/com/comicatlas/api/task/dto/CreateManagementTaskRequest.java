package com.comicatlas.api.task.dto;

import com.comicatlas.api.task.enums.TaskType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 创建管理任务请求。
 */
@Data
public class CreateManagementTaskRequest {

    /** 任务类型 */
    @NotNull
    private TaskType taskType;

    /** 操作描述 */
    @NotBlank
    private String operation;

    /** 目标类型: COMIC/DIRECTORY/SYSTEM */
    private String targetType;

    /** 批次ID */
    private String batchId;

    /** 目标项列表 */
    private List<TaskTarget> targets;

    /**
     * 单个任务目标。
     */
    @Data
    public static class TaskTarget {
        @NotBlank
        private String targetType;

        @NotNull
        private Long targetId;

        private TaskType operationType;
    }
}
