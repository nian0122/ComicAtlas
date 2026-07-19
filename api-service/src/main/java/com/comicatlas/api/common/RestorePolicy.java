package com.comicatlas.api.common;

/**
 * 恢复策略，控制 metadata.json 各字段的处理方式。
 */
public enum RestorePolicy {
    /** 全量导入 — 覆盖 comic 所有字段 */
    IMPORT,
    /** 编辑页刷新 — 仅刷新解析数据，保留用户维护的 title/author/category */
    REFRESH_METADATA
}
