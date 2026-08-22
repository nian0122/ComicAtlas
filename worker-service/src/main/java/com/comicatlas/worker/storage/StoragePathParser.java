package com.comicatlas.worker.storage;

import java.util.OptionalLong;

/** 存储相对路径解析工具，统一处理 MANAGED 路径中的漫画 ID 和目录。 */
public final class StoragePathParser {
    private StoragePathParser() {
    }

    /** 从 {@code comicId/...} 相对路径中解析漫画 ID。 */
    public static OptionalLong parseComicId(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return OptionalLong.empty();
        }
        String firstSegment = relativePath;
        int slashIndex = firstSegment.indexOf('/');
        if (slashIndex > 0) {
            firstSegment = firstSegment.substring(0, slashIndex);
        }
        try {
            return OptionalLong.of(Long.parseLong(firstSegment));
        } catch (NumberFormatException exception) {
            return OptionalLong.empty();
        }
    }

    /** 返回相对路径的父目录；没有父目录时返回原值。 */
    public static String directoryOf(String relativePath) {
        if (relativePath == null) {
            return "";
        }
        int slashIndex = relativePath.lastIndexOf('/');
        return slashIndex > 0 ? relativePath.substring(0, slashIndex) : relativePath;
    }
}
