package com.comicatlas.worker.exporter.exception;

import java.io.IOException;

/** 原件移出尚未完成；任务必须重投恢复，不得发布普通失败结果。 */
public class ExportMoveOutException extends IOException {
    public ExportMoveOutException(String message, Throwable cause) {
        super(message, cause);
    }
}
