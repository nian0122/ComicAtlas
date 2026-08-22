package com.comicatlas.worker.importer.model;

import com.comicatlas.worker.media.ComicMetadata;

import java.util.List;

/**
 * 规范化组装结果：元数据 + 组装警告列表。
 * <p>
 * 空子目录等非致命问题不中断导入，以结构化警告返回；警告后续可映射为
 * {@link com.comicatlas.common.dto.ScanWarningDTO}（ScanWarningCode 语义）。
 */
public record AssembleResult(
    ComicMetadata metadata,
    List<AssembleWarning> warnings
) {

    /** 空目录警告码：对应 Todo 1 ScanWarningCode 的 EMPTY_DIRECTORY 语义。 */
    public static final String CODE_EMPTY_DIRECTORY = "EMPTY_DIRECTORY";

    /**
     * 组装警告：结构化标识（code）+ 人类可读消息 + 相对漫画根的目录路径。
     */
    public record AssembleWarning(
        String code,
        String message,
        String relativePath
    ) {}
}
