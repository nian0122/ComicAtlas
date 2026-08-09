package com.comicatlas.common.dto;

import com.comicatlas.common.storage.RelativePathValidator;

import java.util.List;

/**
 * 目录扫描结果项：父目录下的一个漫画候选子目录。
 * 旧字段 name/path/imageCount 语义保持不变；kind/relativePath/warnings 为可选附加字段：
 * warnings 缺省时规范化为空集合（而非 null），relativePath 只允许正斜杠相对路径。
 */
public record ScanItemDTO(
        String name,
        String path,
        int imageCount,
        ScanNodeKind kind,
        String relativePath,
        List<ScanWarningDTO> warnings) {

    public ScanItemDTO(String name, String path, int imageCount, ScanNodeKind kind,
                       String relativePath, List<ScanWarningDTO> warnings) {
        RelativePathValidator.requireRelativeForwardSlash(relativePath);
        this.name = name;
        this.path = path;
        this.imageCount = imageCount;
        this.kind = kind;
        this.relativePath = relativePath;
        this.warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    /** 旧构造入口（无附加字段），保持向后兼容。 */
    public ScanItemDTO(String name, String path, int imageCount) {
        this(name, path, imageCount, null, null, List.of());
    }
}
