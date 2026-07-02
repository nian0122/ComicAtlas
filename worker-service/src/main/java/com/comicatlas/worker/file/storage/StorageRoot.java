package com.comicatlas.worker.file.storage;

import lombok.Data;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 存储根 — 一个 rootKey（Map 的 key）对应一个物理路径。
 * 不引入 Manager/Repository，直接由 StorageProperties 承载。
 */
@Data
public class StorageRoot {
    private String type = "FILESYSTEM";
    private java.nio.file.Path path;
    private boolean enabled = true;
    private boolean readOnly = false;

    public Path resolve(String relativePath) {
        return path.resolve(relativePath);
    }

    public boolean exists() {
        return path != null && Files.exists(path);
    }
}
