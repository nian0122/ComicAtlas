package com.comicatlas.api.common.enums;

/**
 * 章节生命周期状态。
 * <p>
 * 终态：DELETED。DRAFT/TRASHED/DELETED 不出现在阅读列表。
 */
public enum ChapterLifecycleStatus {
    /** Worker 处理中，尚未写入元数据 */
    DRAFT,
    /** 正常可读 */
    READY,
    /** 删除排队中 */
    DELETING,
    /** 回收中：文件按清单移入 TRASH */
    TRASHING,
    /** 已软删除 */
    TRASHED,
    /** 恢复中 */
    RESTORING,
    /** 物理删除排队中 */
    PURGING,
    /** 已永久删除（终态） */
    DELETED;

    public boolean isTerminal() {
        return this == DELETED;
    }

    public boolean isTransient() {
        return this == DELETING || this == TRASHING || this == RESTORING || this == PURGING;
    }
}
