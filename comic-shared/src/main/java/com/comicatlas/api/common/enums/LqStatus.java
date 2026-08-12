package com.comicatlas.api.common.enums;

/**
 * LQ（低清 WebP）生成状态。
 * <p>
 * MIXED/EMPTY 仅用于聚合 DTO，禁止写入实体状态列。
 */
public enum LqStatus {
    NOT_GENERATED,
    QUEUED,
    GENERATING,
    READY,
    MISSING,
    FAILED;

    public boolean isTerminal() { return this == FAILED; }
    public boolean isProcessing() { return this == QUEUED || this == GENERATING; }
}
