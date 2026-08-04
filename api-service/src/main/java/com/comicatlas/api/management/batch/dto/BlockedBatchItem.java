package com.comicatlas.api.management.batch.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 被阻止的批量目标项。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BlockedBatchItem {

    private Long comicId;

    /** 稳定 reasonCode：OP_NOT_ALLOWED / COMIC_NOT_FOUND 等 */
    private String reasonCode;

    private String reason;
}
