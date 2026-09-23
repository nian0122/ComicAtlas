package com.comicatlas.worker.importer.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 导入清单（恢复点）。
 * files[].source 为相对 sourceRoot 的相对路径；files[].target 为 HQ 相对路径（comicId/chapterGlobalOrder/fileName）。
 * metadata 为完整 v3 metadata（含 MediaAnalyzer 提取的文件元信息），恢复时零依赖源文件。
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class ImportManifest {
    private final int version;
    private final long taskId;
    private final String sourceType;
    private final String sourceRoot;
    private final JsonNode metadata;
    private final List<ImportFile> files;

    @JsonCreator
    public ImportManifest(@JsonProperty("version") int version,
                           @JsonProperty("taskId") long taskId,
                           @JsonProperty("sourceType") String sourceType,
                           @JsonProperty("sourceRoot") String sourceRoot,
                           @JsonProperty("metadata") JsonNode metadata,
                           @JsonProperty("files") List<ImportFile> files) {
        this.version = version;
        this.taskId = taskId;
        this.sourceType = sourceType;
        this.sourceRoot = sourceRoot;
        this.metadata = metadata;
        this.files = files;
    }

    public int version() { return version; }
    public long taskId() { return taskId; }
    public String sourceType() { return sourceType; }
    public String sourceRoot() { return sourceRoot; }
    public JsonNode metadata() { return metadata; }
    public List<ImportFile> files() { return files; }

    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    public static final class ImportFile {
        private final String source;
        private final String target;
        private final long size;

        @JsonCreator
        public ImportFile(@JsonProperty("source") String source,
                          @JsonProperty("target") String target,
                          @JsonProperty("size") long size) {
            this.source = source;
            this.target = target;
            this.size = size;
        }

        public String source() { return source; }
        public String target() { return target; }
        public long size() { return size; }
    }
}
