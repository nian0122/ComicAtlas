package com.comicatlas.worker.storage;

/**
 * 路径穿越异常 — 当相对路径包含 {@code ../} 穿越存储根边界，或经 symlink/junction
 * 逃出根的真实边界时抛出。这是一个 typed error，调用方必须处理而非静默忽略。
 */
public class PathTraversalException extends RuntimeException {
    public PathTraversalException(String message) {
        super(message);
    }

    public PathTraversalException(String message, Throwable cause) {
        super(message, cause);
    }
}
