package com.comicatlas.contract.common.enums;

/**
 * 视频转码状态 — 跟踪视频页面的浏览器兼容格式转换。
 * <p>
 * 默认 NOT_NEEDED（非视频页面或兼容格式视频无需转码）。
 */
public enum TranscodeStatus {
    /** 无需转码（图片页或兼容格式视频） */
    NOT_NEEDED,
    /** 需要转码但未进队列（等待用户手动触发） */
    REQUIRED,
    /** 排队等待 Worker 转码 */
    QUEUED,
    /** Worker 正在转码 */
    TRANSCODING,
    /** 转码完成 */
    READY,
    /** 转码失败 */
    FAILED;

    public boolean isTerminal() {
        return this == NOT_NEEDED || this == FAILED;
    }

    public boolean isProcessing() {
        return this == QUEUED || this == TRANSCODING;
    }
}
