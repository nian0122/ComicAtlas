package com.comicatlas.common.dto;

import java.util.List;

/**
 * 目录扫描结果：父目录及其下的漫画候选子目录列表。
 * 旧字段 parentPath/total/items 语义保持不变；preview/warnings 为可选附加字段，
 * 缺省时规范化为空集合（而非 null）。
 */
public record ScanResultDTO(
        String parentPath,
        int total,
        List<ScanItemDTO> items,
        List<ScanPreviewNodeDTO> preview,
        List<ScanWarningDTO> warnings) {

    public ScanResultDTO(String parentPath, int total, List<ScanItemDTO> items,
                         List<ScanPreviewNodeDTO> preview, List<ScanWarningDTO> warnings) {
        this.parentPath = parentPath;
        this.total = total;
        this.items = items == null ? List.of() : List.copyOf(items);
        this.preview = preview == null ? List.of() : List.copyOf(preview);
        this.warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    /** 旧构造入口（无附加字段），保持向后兼容。 */
    public ScanResultDTO(String parentPath, int total, List<ScanItemDTO> items) {
        this(parentPath, total, items, List.of(), List.of());
    }
}
