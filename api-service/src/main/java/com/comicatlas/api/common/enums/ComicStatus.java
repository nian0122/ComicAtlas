package com.comicatlas.api.common.enums;

/**
 * 漫画状态（兼容旧枚举名，值对齐 {@link com.comicatlas.common.enums.ComicLifecycleStatus}）。
 * <p>
 * 终态：DELETED。DRAFT/TRASHED/DELETED 不出现在阅读列表。
 */
public enum ComicStatus {
    DRAFT,
    IMPORTING,
    IMPORT_FAILED,
    READY,
    RECOVERY_REQUIRED,
    DELETING,
    TRASHED,
    RESTORING,
    PURGING,
    DELETED;

    public boolean isTerminal() { return this == DELETED; }
}
