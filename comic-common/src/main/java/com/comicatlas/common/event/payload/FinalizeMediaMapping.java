package com.comicatlas.common.event.payload;

/**
 * 导入存储最终化的单个媒体映射（相对路径对）。
 *
 * <p>{@code sourcePath} 相对最终化请求的 {@code sourceDir}，{@code targetPath} 相对
 * {@code targetDir}，两者均禁止绝对路径；Worker 据此刻度把媒体文件从源位置搬到目标位置。
 */
public record FinalizeMediaMapping(
    String sourcePath,
    String targetPath
) {
}
