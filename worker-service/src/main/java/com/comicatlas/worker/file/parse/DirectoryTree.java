package com.comicatlas.worker.file.parse;

import java.nio.file.Path;
import java.util.List;

/**
 * 纯目录结构，不包含业务语义（Catalog/Chapter）。
 * Parser 输出此对象，MetadataAssembler 负责转换为 ComicMetadata。
 */
public record DirectoryTree(
    Path path,
    String name,
    List<Path> mediaFiles,           // 当前目录下的媒体文件（图片 + 视频，叶子节点有值）
    List<DirectoryTree> children     // 子目录
) {
    public boolean isLeaf() {
        return mediaFiles != null && !mediaFiles.isEmpty();
    }

    public boolean hasChildren() {
        return children != null && !children.isEmpty();
    }

    @Deprecated
    public List<Path> imageFiles() {
        return mediaFiles;
    }
}
