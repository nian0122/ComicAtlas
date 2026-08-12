package com.comicatlas.contract.common.enums;

/**
 * 媒体页（IMAGE/VIDEO）生命周期状态。
 * <p>
 * 终态：DELETED。STAGING/TRASHED/DELETED 不出现在阅读列表。
 */
public enum MediaLifecycleStatus {
    /** Worker 处理中，文件尚未写入最终位置 */
    STAGING,
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
