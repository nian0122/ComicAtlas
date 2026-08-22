package com.comicatlas.worker.importer.exception;

/**
 * 目录解析的确定失败异常。
 * <p>
 * 携带 {@link DirectoryParseError} 错误类型，供真实导入 typed-fail、
 * 预览场景转为结构化阻断 warning。异常信息只含相对路径/文件名，不泄露
 * 宿主机绝对路径。
 */
public class DirectoryParseException extends RuntimeException {

    private final DirectoryParseError error;

    public DirectoryParseException(DirectoryParseError error, String message) {
        super(message);
        this.error = error;
    }

    public DirectoryParseException(DirectoryParseError error, String message, Throwable cause) {
        super(message, cause);
        this.error = error;
    }

    public DirectoryParseError error() {
        return error;
    }
}
