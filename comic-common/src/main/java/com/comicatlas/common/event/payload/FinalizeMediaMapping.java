package com.comicatlas.common.event.payload;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * 导入存储最终化的单个媒体映射（相对路径对）。
 *
 * <p>{@code sourcePath} 相对最终化请求的 {@code sourceDir}，{@code targetPath} 相对
 * {@code targetDir}，两者均禁止绝对路径；Worker 据此刻度把媒体文件从源位置搬到目标位置。
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class FinalizeMediaMapping {
    private final String sourcePath;
    private final String targetPath;

    @JsonCreator
    public FinalizeMediaMapping(@JsonProperty("sourcePath") String sourcePath,
                                @JsonProperty("targetPath") String targetPath) {
        this.sourcePath = sourcePath;
        this.targetPath = targetPath;
    }

    public String sourcePath() { return sourcePath; }
    public String targetPath() { return targetPath; }

    @Override
    public boolean equals(Object other) {
        if (this == other) { return true; }
        if (!(other instanceof FinalizeMediaMapping that)) { return false; }
        return Objects.equals(sourcePath, that.sourcePath) && Objects.equals(targetPath, that.targetPath);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sourcePath, targetPath);
    }
}
