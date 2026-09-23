package com.comicatlas.common.dto;

import com.comicatlas.common.storage.RelativePathValidator;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 目录预览节点：扫描目录树中的一个节点，包含名称、节点类型、正斜杠相对路径、
 * 文件数与子节点/警告。
 * relativePath 必须是正斜杠相对路径，禁止绝对路径；
 * children/warnings 缺省时规范化为空集合（而非 null）。
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class ScanPreviewNodeDTO {
    private final String name;
    private final ScanNodeKind kind;
    private final String relativePath;
    private final int fileCount;
    private final List<ScanPreviewNodeDTO> children;
    private final List<ScanWarningDTO> warnings;

    @JsonCreator
    public ScanPreviewNodeDTO(@JsonProperty("name") String name, @JsonProperty("kind") ScanNodeKind kind,
                              @JsonProperty("relativePath") String relativePath,
                              @JsonProperty("fileCount") int fileCount,
                              @JsonProperty("children") List<ScanPreviewNodeDTO> children,
                              @JsonProperty("warnings") List<ScanWarningDTO> warnings) {
        RelativePathValidator.requireRelativeForwardSlash(relativePath);
        this.name = name;
        this.kind = kind;
        this.relativePath = relativePath;
        this.fileCount = fileCount;
        this.children = children == null ? List.of() : List.copyOf(children);
        this.warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public String name() { return name; }
    public ScanNodeKind kind() { return kind; }
    public String relativePath() { return relativePath; }
    public int fileCount() { return fileCount; }
    public List<ScanPreviewNodeDTO> children() { return children; }
    public List<ScanWarningDTO> warnings() { return warnings; }
}
