package com.comicatlas.common.dto;

import com.comicatlas.common.storage.RelativePathValidator;

import java.util.List;

/**
 * 目录预览节点：扫描目录树中的一个节点，包含名称、节点类型、正斜杠相对路径、
 * 文件数与子节点/警告。
 * relativePath 必须是正斜杠相对路径，禁止绝对路径；
 * children/warnings 缺省时规范化为空集合（而非 null）。
 */
public record ScanPreviewNodeDTO(
        String name,
        ScanNodeKind kind,
        String relativePath,
        int fileCount,
        List<ScanPreviewNodeDTO> children,
        List<ScanWarningDTO> warnings) {

    public ScanPreviewNodeDTO(String name, ScanNodeKind kind, String relativePath,
                              int fileCount, List<ScanPreviewNodeDTO> children,
                              List<ScanWarningDTO> warnings) {
        RelativePathValidator.requireRelativeForwardSlash(relativePath);
        this.name = name;
        this.kind = kind;
        this.relativePath = relativePath;
        this.fileCount = fileCount;
        this.children = children == null ? List.of() : List.copyOf(children);
        this.warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
