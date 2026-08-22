package com.comicatlas.api.task.batch;

/**
 * 批量操作稳定 reasonCode 常量。
 * <p>
 * 前端与测试依赖这些稳定码判断失败原因，禁止随意变更。
 */
public final class BatchReasonCode {

    private BatchReasonCode() {
    }

    /** 筛选结果为空 */
    public static final String EMPTY_SELECTION = "EMPTY_SELECTION";
    /** 命中数量超过 max-items 上限 */
    public static final String BATCH_SIZE_EXCEEDED = "BATCH_SIZE_EXCEEDED";
    /** 危险操作缺少二次确认 preview token */
    public static final String PREVIEW_TOKEN_REQUIRED = "PREVIEW_TOKEN_REQUIRED";
    /** preview token 已过期 */
    public static final String PREVIEW_TOKEN_EXPIRED = "PREVIEW_TOKEN_EXPIRED";
    /** preview 后条件变化（新增/删除/状态翻转），需重新确认 */
    public static final String PREVIEW_CONDITION_CHANGED = "PREVIEW_CONDITION_CHANGED";
    /** 幂等键冲突（同键不同 payload） */
    public static final String IDEMPOTENCY_CONFLICT = "IDEMPOTENCY_CONFLICT";
    /** 单个目标不允许该操作 */
    public static final String OP_NOT_ALLOWED = "OP_NOT_ALLOWED";
    /** 目标漫画不存在 */
    public static final String COMIC_NOT_FOUND = "COMIC_NOT_FOUND";
}
