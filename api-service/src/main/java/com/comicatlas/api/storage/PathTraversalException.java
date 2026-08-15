package com.comicatlas.api.storage;

/**
 * 路径穿越异常 — 存储根边界校验失败时抛出。
 */
public class PathTraversalException extends RuntimeException {
    public PathTraversalException(String message) {
        super(message);
    }
}
