package com.comicatlas.worker.storage;

import java.nio.file.Path;

/** MANAGED 存储路径解析器，统一根目录拼接并防止相对路径越界。 */
public final class ManagedStoragePath {

    private ManagedStoragePath() {
    }

    /**
     * 解析存储根下的相对路径。
     *
     * @param managedRoot MANAGED 总根目录
     * @param rootKey 存储根键，例如 HQ/LQ
     * @param relativePath 数据库或事件携带的相对路径
     * @return 规范化后的文件路径
     */
    public static Path resolve(Path managedRoot, String rootKey, String relativePath) {
        if (managedRoot == null || rootKey == null || rootKey.isBlank()
                || relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("MANAGED 存储路径参数不能为空");
        }
        Path root = managedRoot.resolve(rootKey).normalize();
        Path resolved = root.resolve(relativePath).normalize();
        if (!resolved.startsWith(root)) {
            throw new PathTraversalException("存储路径越界: rootKey=" + rootKey);
        }
        return resolved;
    }
}
