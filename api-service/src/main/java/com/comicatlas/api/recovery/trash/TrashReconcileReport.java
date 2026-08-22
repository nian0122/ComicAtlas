package com.comicatlas.api.recovery.trash;

import java.util.List;

/**
 * TRASH 对账报告 — 展示 DB 状态、清单意图与实际磁盘的一致程度。
 */
public record TrashReconcileReport(
    String targetType,
    Long targetId,
    String dbStatus,
    Long manifestTaskId,
    String manifestStatus,
    boolean consistent,
    List<EntryReport> entries
) {

    public record EntryReport(
        String rootKey,
        String sourceRelativePath,
        boolean sourceExists,
        boolean trashExists,
        String state
    ) {
    }
}
