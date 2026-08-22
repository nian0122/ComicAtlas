package com.comicatlas.worker.exporter;

/**
 * 导出发布冲突异常 — 最终任务目录已存在且与本次 manifest 不一致。
 * 绝不覆盖或删除既有最终目录，整个导出任务失败。
 */
public class ExportPublishConflictException extends ExportPublishException {

    public ExportPublishConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
