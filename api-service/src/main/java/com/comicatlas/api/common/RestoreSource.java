package com.comicatlas.api.common;

/**
 * 恢复来源，表示触发恢复操作的入口。
 */
public enum RestoreSource {
    /** metadata.json (Phase 1) */
    METADATA,
    /** HQ Package (Phase 2) */
    HQ_PACKAGE,
    /** 正常导入 */
    IMPORT,
    /** 扫描恢复 */
    SCAN
}
