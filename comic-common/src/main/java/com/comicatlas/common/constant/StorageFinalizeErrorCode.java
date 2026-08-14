package com.comicatlas.common.constant;

/**
 * 导入存储最终化错误码常量（契约：Worker 发布 {@code ImportStorageFinalizeFailedEvent} 时携带，
 * API 依据错误码标记失败原因；值保持与历史事件/测试断言一致，禁止修改）。
 * <p>
 * Worker 与 API 共享同一错误码字典，业务代码禁止硬编码错误码字符串。
 */
public final class StorageFinalizeErrorCode {
    private StorageFinalizeErrorCode() {
    }

    /** 目标存在但尺寸与清单不符。 */
    public static final String SIZE_CONFLICT = "STORAGE_FINALIZE_SIZE_CONFLICT";
    /** 源与目标同时存在。 */
    public static final String CONFLICT = "STORAGE_FINALIZE_CONFLICT";
    /** 源与目标均缺失。 */
    public static final String SOURCE_MISSING = "STORAGE_FINALIZE_SOURCE_MISSING";
    /** 清单缺失且目标不完整。 */
    public static final String MANIFEST_MISSING = "STORAGE_FINALIZE_MANIFEST_MISSING";
    /** 相对路径越出 HQ 根。 */
    public static final String PATH_OUTSIDE_HQ = "STORAGE_FINALIZE_PATH_OUTSIDE_HQ";
    /** 相对路径为空或为绝对路径。 */
    public static final String INVALID_PATH = "STORAGE_FINALIZE_INVALID_PATH";
    /** 未归类异常。 */
    public static final String UNEXPECTED = "STORAGE_FINALIZE_UNEXPECTED";
}
