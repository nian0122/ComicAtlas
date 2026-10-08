package com.comicatlas.common.dto;

import com.comicatlas.common.storage.RelativePathValidator;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 目录扫描结果项：父目录下的一个漫画候选子目录。
 * 旧字段 name/path/imageCount 语义保持不变；kind/relativePath/warnings 为可选附加字段：
 * warnings 缺省时规范化为空集合（而非 null），relativePath 只允许正斜杠相对路径。
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class ScanItemDTO {
    private final String name;
    private final String path;
    private final int imageCount;
    private final ScanNodeKind kind;
    private final String relativePath;
    private final List<ScanWarningDTO> warnings;

    @JsonCreator
    public ScanItemDTO(@JsonProperty("name") String name, @JsonProperty("path") String path,
                       @JsonProperty("imageCount") int imageCount, @JsonProperty("kind") ScanNodeKind kind,
                       @JsonProperty("relativePath") String relativePath,
                       @JsonProperty("warnings") List<ScanWarningDTO> warnings) {
        RelativePathValidator.requireRelativeForwardSlash(relativePath);
        this.name = name;
        this.path = path;
        this.imageCount = imageCount;
        this.kind = kind;
        this.relativePath = relativePath;
        this.warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public String name() { return name; }
    public String path() { return path; }
    public int imageCount() { return imageCount; }
    public ScanNodeKind kind() { return kind; }
    public String relativePath() { return relativePath; }
    public List<ScanWarningDTO> warnings() { return warnings; }

    /** 旧构造入口（无附加字段），保持向后兼容。 */
    public ScanItemDTO(String name, String path, int imageCount) {
        this(name, path, imageCount, null, null, List.of());
    }
}
