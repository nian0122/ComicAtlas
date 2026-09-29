package com.comicatlas.api.task.dto;

import com.comicatlas.api.task.enums.ManagementTaskStatus;
import lombok.Data;

/** 管理任务按状态聚合的全量数量。 */
@Data
public class ManagementTaskStatusCountResponse {
    private ManagementTaskStatus status;
    private long taskCount;
}
