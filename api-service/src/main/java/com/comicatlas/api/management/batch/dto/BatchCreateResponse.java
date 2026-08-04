package com.comicatlas.api.management.batch.dto;

import com.comicatlas.api.management.dto.ManagementTaskResponse;
import lombok.Data;

import java.util.List;

/**
 * 批量任务创建结果。
 */
@Data
public class BatchCreateResponse {

    /** 已创建的统一管理任务（含逐目标 item） */
    private ManagementTaskResponse task;

    private int selectedCount;

    private int eligibleCount;

    private List<BlockedBatchItem> blocked;
}
