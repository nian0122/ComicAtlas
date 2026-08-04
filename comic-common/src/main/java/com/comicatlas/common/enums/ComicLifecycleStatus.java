package com.comicatlas.common.enums;

/**
 * 漫画生命周期状态 — 控制整本漫画的 CRUD 操作权限。
 * <p>
 * 终态：DELETED（不可逆）。
 * DRAFT/TRASHED/DELETED 不应出现在阅读列表。
 */
public enum ComicLifecycleStatus {
    /** 预创建，未开始导入 */
    DRAFT,
    /** 导入进行中（Worker 处理文件→写入章节/媒体） */
    IMPORTING,
    /** 导入失败（可重试或删除） */
    IMPORT_FAILED,
    /** 正常可读 */
    READY,
    /** 文件存在但 DB 记录缺失，等待恢复扫描 */
    RECOVERY_REQUIRED,
    /** 删除排队中（Worker 尚未开始删文件） */
    DELETING,
    /** 回收中：文件按清单同卷移入 TRASH（部分失败可补偿回滚） */
    TRASHING,
    /** 已删除但可恢复（软删除） */
    TRASHED,
    /** 恢复中 */
    RESTORING,
    /** 物理删除排队中 */
    PURGING,
    /** 已永久删除（终态） */
    DELETED;

    /** 终态：到达后不可再迁移 */
    public boolean isTerminal() {
        return this == DELETED;
    }

    /** 是否在阅读列表中可见 */
    public boolean isReadable() {
        return this == READY;
    }

    /** 是否处于过渡状态（不应接受用户操作） */
    public boolean isTransient() {
        return this == IMPORTING || this == DELETING || this == TRASHING
                || this == RESTORING || this == PURGING;
    }
}
