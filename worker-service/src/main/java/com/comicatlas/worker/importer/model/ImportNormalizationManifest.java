package com.comicatlas.worker.importer.model;

import com.comicatlas.worker.media.ComicMetadata;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonProperty;

/** 来源整理恢复点：保存逻辑目录树对应的章节计划，重试不再从已移动的来源目录猜结构。 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class ImportNormalizationManifest {
    private final int version;
    private final long taskId;
    private final String sourceType;
    private final String sourceRoot;
    private final String stagingComicRoot;
    private final ComicMetadata plannedMetadata;

    @JsonCreator
    public ImportNormalizationManifest(@JsonProperty("version") int version,
                                       @JsonProperty("taskId") long taskId,
                                       @JsonProperty("sourceType") String sourceType,
                                       @JsonProperty("sourceRoot") String sourceRoot,
                                       @JsonProperty("stagingComicRoot") String stagingComicRoot,
                                       @JsonProperty("plannedMetadata") ComicMetadata plannedMetadata) {
        this.version = version;
        this.taskId = taskId;
        this.sourceType = sourceType;
        this.sourceRoot = sourceRoot;
        this.stagingComicRoot = stagingComicRoot;
        this.plannedMetadata = plannedMetadata;
    }

    public int version() { return version; }
    public long taskId() { return taskId; }
    public String sourceType() { return sourceType; }
    public String sourceRoot() { return sourceRoot; }
    public String stagingComicRoot() { return stagingComicRoot; }
    public ComicMetadata plannedMetadata() { return plannedMetadata; }
}
