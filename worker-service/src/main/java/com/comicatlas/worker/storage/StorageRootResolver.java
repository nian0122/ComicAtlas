package com.comicatlas.worker.storage;

import java.util.Map;

/** 统一存储根访问和配置校验，避免业务代码散落字符串 key 与 null 判断。 */
public final class StorageRootResolver {
    private StorageRootResolver() {
    }

    /** 获取必需的存储根；未配置时抛出可定位的业务异常。 */
    public static StorageRoot required(StorageProperties properties, String rootKey) {
        if (properties == null || properties.getRoots() == null) {
            throw new IllegalStateException("存储根配置未加载: " + rootKey);
        }
        StorageRoot root = properties.getRoots().get(rootKey);
        if (root == null || root.getPath() == null || !root.isEnabled()) {
            throw new IllegalStateException("存储根未配置或未启用: " + rootKey);
        }
        return root;
    }

    /** 获取可选存储根；用于允许缺少某类产物的读取场景。 */
    public static StorageRoot optional(StorageProperties properties, String rootKey) {
        Map<String, StorageRoot> roots = properties == null ? null : properties.getRoots();
        return roots == null ? null : roots.get(rootKey);
    }
}
