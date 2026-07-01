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
    List<Path> imageFiles,           // 当前目录下的图片文件（叶子节点有值）
    List<DirectoryTree> children     // 子目录
) {
    public boolean isLeaf() {
        return imageFiles != null && !imageFiles.isEmpty();
    }

    public boolean hasChildren() {
        return children != null && !children.isEmpty();
    }
}
