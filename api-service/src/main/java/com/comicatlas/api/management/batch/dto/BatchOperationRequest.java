package com.comicatlas.api.management.batch.dto;

import com.comicatlas.api.common.enums.TaskType;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 批量操作请求。
 */
@Data
public class BatchOperationRequest {

    /** 批量操作类型：METADATA_UPDATE / LQ_GENERATE / HQ_DELETE / TRANSCODE / COMIC_DELETE / COMIC_RESTORE / COMIC_PURGE */
    @NotNull(message = "operation 不能为空")
    private TaskType operation;

    /** 目标选择（IDS 或 FILTER 判别联合） */
    @NotNull(message = "selection 不能为空")
    private BatchSelection selection;

    /** 批量操作负载（METADATA_UPDATE 的分类/标签；其他操作为空） */
    private BatchOperationPayload payload;

    /** 危险操作二次确认 token（由 preview 接口签发） */
    private String previewToken;
}
