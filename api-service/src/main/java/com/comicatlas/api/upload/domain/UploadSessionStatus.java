package com.comicatlas.api.upload.domain;

/**
 * 上传会话生命周期状态。
 */
public enum UploadSessionStatus {
    /** 分片上传进行中 */
    ACTIVE,
    /** complete 已调用，命令已派发（STAGING 文件仍保留等待 Worker 搬移） */
    COMPLETED,
    /** 用户取消 */
    CANCELLED,
    /** 24h 未完成自动过期 */
    EXPIRED,
    /** 命令处理失败 */
    FAILED
}
