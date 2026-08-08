package com.comicatlas.api.management.batch.dto;

import com.comicatlas.api.common.enums.TaskType;
import lombok.Data;

import java.time.Instant;
import java.util.List;

/**
 * 批量选择预览结果。
 * <p>
 * selectedCount = 筛选命中的总漫画数；
 * eligibleCount = 通过资格校验、将物化为 item 的数量；
 * blocked = 命中选择但被阻止的漫画及稳定 reasonCode；
 * dangerous + previewToken：危险操作（如 COMIC_PURGE）需二次确认。
 */
@Data
public class BatchPreviewResponse {

    private TaskType operation;

    private int selectedCount;

    private int eligibleCount;

    private List<BlockedBatchItem> blocked;

    private boolean dangerous;

    private String previewToken;

    private Instant expiresAt;
}
