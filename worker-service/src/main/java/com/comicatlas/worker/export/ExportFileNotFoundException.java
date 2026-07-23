package com.comicatlas.worker.export;

/**
 * 导出文件无法解析时抛出 — HQ 缺失且 LQ 也未就绪。
 */
public class ExportFileNotFoundException extends RuntimeException {

    public ExportFileNotFoundException(String message) {
        super(message);
    }
}
