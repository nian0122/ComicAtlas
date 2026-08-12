package com.comicatlas.api.common.storage;

import lombok.Data;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * API 侧存储根 — 对应 application.yml 中 storage.roots 下的每个 key。
 * API 对 STAGING 可写，对其他根只读。
 *
 * <p>路径解析强制防御 {@code ../} 穿越。
 */
@Data
public class ApiStorageRoot {
    private String type = "FILESYSTEM";
    private Path path;
    private boolean enabled = true;
    private boolean readOnly = true;

    /**
     * 安全解析相对路径，防御路径穿越攻击。
     *
     * @param relativePath 相对路径
     * @return 解析后的绝对路径
     * @throws PathTraversalException 路径穿越 root 边界
     */
    public Path resolve(String relativePath) {
        if (path == null) {
            throw new IllegalStateException("存储根路径未配置");
        }
        Path resolved = path.resolve(relativePath).normalize();
        if (!resolved.startsWith(path.normalize())) {
            throw new PathTraversalException("路径穿越拒绝: root=" + path + ", relative=" + relativePath);
        }
        return resolved;
    }

    public boolean exists() {
        return path != null && Files.exists(path);
    }

    /**
     * 判断指定路径与此存储根是否在同一文件系统卷上。
     */
    public boolean sameFileStore(Path other) {
        if (path == null || other == null) { return false; }
        try {
            return Files.getFileStore(path).equals(Files.getFileStore(other));
        } catch (Exception e) {
            return false;
        }
    }
}
