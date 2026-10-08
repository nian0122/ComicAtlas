package com.comicatlas.api.storage.enums;

/** 缩略图容量快照刷新状态；失败时继续保留上次成功结果。 */
public enum SnapshotRefreshStatus {
    /** 等待后台扫描。 */
    PENDING,
    /** 正在扫描。 */
    RUNNING,
    /** 最近请求已完成。 */
    READY,
    /** 最近扫描失败。 */
    FAILED
}
