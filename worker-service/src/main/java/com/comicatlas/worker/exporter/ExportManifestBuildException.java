package com.comicatlas.worker.exporter;

/**
 * 导出清单预检失败时抛出 — 任一媒体无可用且可读的普通文件、ZIP 目标路径冲突、
 * 或条目/总量超出容量上限，整个导出立即失败。
 *
 * <p>消息只携带 comicId/mediaId 与相对 targetPath，禁止输出宿主机绝对路径。
 */
public class ExportManifestBuildException extends RuntimeException {

    public ExportManifestBuildException(String message) {
        super(message);
    }

    public ExportManifestBuildException(String message, Throwable cause) {
        super(message, cause);
    }
}
