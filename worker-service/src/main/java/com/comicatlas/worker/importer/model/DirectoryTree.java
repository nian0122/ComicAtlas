package com.comicatlas.worker.importer.model;

import java.nio.file.Path;
import java.util.List;

/**
 * 纯目录结构，不包含业务语义（Catalog/Chapter）。
 * Parser 输出此对象，MetadataAssembler 负责转换为 ComicMetadata。
 */
public final class DirectoryTree {
    private final Path path;
    private final String name;
    private final List<Path> mediaFiles;
    private final List<DirectoryTree> children;

    public DirectoryTree(Path path, String name, List<Path> mediaFiles, List<DirectoryTree> children) {
        this.path = path;
        this.name = name;
        this.mediaFiles = mediaFiles;
        this.children = children;
    }

    public Path path() { return path; }
    public String name() { return name; }
    public List<Path> mediaFiles() { return mediaFiles; }
    public List<DirectoryTree> children() { return children; }

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
