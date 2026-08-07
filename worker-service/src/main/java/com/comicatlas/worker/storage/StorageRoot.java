package com.comicatlas.worker.storage;

import lombok.Data;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 存储根 — 一个 rootKey（Map 的 key）对应一个物理路径。
 * 不引入 Manager/Repository，直接由 StorageProperties 承载。
 *
 * <p>路径解析强制防御 {@code ../} 穿越：resolve() 会先 normalize 再校验结果必须在 root 内。
 */
@Data
public class StorageRoot {
    private String type = "FILESYSTEM";
    private java.nio.file.Path path;
    private boolean enabled = true;
    private boolean readOnly = false;

    /**
     * 安全解析相对路径，防御路径穿越攻击。
     *
     * @param relativePath 相对路径，不得包含 {@code ..} 穿越 root 边界
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
     * 同卷可用原子 rename；跨卷需 copy+delete，且需显式确认允许跨卷操作。
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
