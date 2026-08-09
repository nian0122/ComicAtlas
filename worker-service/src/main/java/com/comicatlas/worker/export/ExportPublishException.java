package com.comicatlas.worker.export;

import java.io.IOException;

/**
 * 导出发布异常 — 发布最终任务目录失败（如不支持原子移动）。
 * 消息携带 taskId 与原因，cause 保留原始异常。
 */
public class ExportPublishException extends IOException {

    public ExportPublishException(String message, Throwable cause) {
        super(message, cause);
    }
}
