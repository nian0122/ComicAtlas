package com.comicatlas.api.common.enums;

/**
 * HQ（高清）文件状态。
 * <p>
 * MIXED/EMPTY 仅用于聚合 DTO，禁止写入实体状态列。
 */
public enum HqStatus {
    PENDING,
    READY,
    MISSING,
    DELETE_QUEUED,
    DELETING,
    DELETED,
    FAILED;

    public boolean isTerminal() { return this == DELETED || this == FAILED; }
}
