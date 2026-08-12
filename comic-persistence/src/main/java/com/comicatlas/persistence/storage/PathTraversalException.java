package com.comicatlas.persistence.storage;

/**
 * 路径穿越异常 — 当相对路径包含 {@code ../} 穿越存储根边界时抛出。
 */
public class PathTraversalException extends RuntimeException {
    public PathTraversalException(String message) {
        super(message);
    }
}
