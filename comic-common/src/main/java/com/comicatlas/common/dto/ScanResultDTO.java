package com.comicatlas.common.dto;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 目录扫描结果：父目录及其下的漫画候选子目录列表。
 * 旧字段 parentPath/total/items 语义保持不变；preview/warnings 为可选附加字段，
 * 缺省时规范化为空集合（而非 null）。
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class ScanResultDTO {
    private final String parentPath;
    private final int total;
    private final List<ScanItemDTO> items;
    private final List<ScanPreviewNodeDTO> preview;
    private final List<ScanWarningDTO> warnings;

    @JsonCreator
    public ScanResultDTO(@JsonProperty("parentPath") String parentPath, @JsonProperty("total") int total,
                         @JsonProperty("items") List<ScanItemDTO> items,
                         @JsonProperty("preview") List<ScanPreviewNodeDTO> preview,
                         @JsonProperty("warnings") List<ScanWarningDTO> warnings) {
        this.parentPath = parentPath;
        this.total = total;
        this.items = items == null ? List.of() : List.copyOf(items);
        this.preview = preview == null ? List.of() : List.copyOf(preview);
        this.warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public String parentPath() { return parentPath; }
    public int total() { return total; }
    public List<ScanItemDTO> items() { return items; }
    public List<ScanPreviewNodeDTO> preview() { return preview; }
    public List<ScanWarningDTO> warnings() { return warnings; }

    /** 旧构造入口（无附加字段），保持向后兼容。 */
    public ScanResultDTO(String parentPath, int total, List<ScanItemDTO> items) {
        this(parentPath, total, items, List.of(), List.of());
    }
}
