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
    /** 元数据刷新中（AdminServiceImpl CAS 锁中间态） */
    REFRESHING,
    /** 删除排队中（Worker 尚未开始删文件） */
    DELETING,
    /** 回收中：文件按清单同卷移入 TRASH（部分失败可补偿回滚） */
    TRASHING,
    /** 已删除但可恢复（软删除） */
    TRASHED,
    RESTORING,
    PURGING,
    DELETED;

    public boolean isTerminal() { return this == DELETED; }
}
