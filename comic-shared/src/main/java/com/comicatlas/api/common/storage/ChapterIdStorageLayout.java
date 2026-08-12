package com.comicatlas.api.common.storage;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import java.util.UUID;

/**
 * 新媒体布局：{@code {comicId}/{chapterId}/{serverGeneratedName}}。
 *
 * <p>文件名由服务器生成（UUID + 原扩展名），避免文件名冲突和路径猜解。
 * 旧布局 {@code {comicId}/{globalOrder}/{imageName}} 兼容由 DB 中真实 root+path 保证，
 * 不强制搬迁旧文件。
 */
@Primary
@Component
public class ChapterIdStorageLayout implements StorageLayout {

    /**
     * 生成新布局路径：{@code {comicId}/{chapterId}/{uuid}.{ext}}。
     *
     * @param comicId   漫画 ID
     * @param chapterId 章节 ID
     * @param imageName 原始文件名（用于提取扩展名）
     * @return 如 {@code 42/100/a1b2c3d4-e5f6.jpg}
     */
    @Override
    public String forPage(Long comicId, Long chapterId, String imageName) {
        String ext = extractExtension(imageName);
        String generatedName = UUID.randomUUID().toString() + ext;
        return comicId + "/" + chapterId + "/" + generatedName;
    }

    /**
     * 从文件名提取扩展名（含点号）。
     */
    public static String extractExtension(String fileName) {
        if (fileName == null || fileName.isBlank()) { return ""; }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) { return ""; }
        return fileName.substring(dot).toLowerCase();
    }
}
