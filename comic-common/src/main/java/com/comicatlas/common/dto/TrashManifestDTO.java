package com.comicatlas.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

/**
 * TRASH 资产清单（不可变，API 基于 DB refs 创建）。
 * <p>
 * 存放于 {@code TRASH/{targetType}/{targetId}/{taskId}/manifest.json}。
 * Worker 严格按 entries 移动，绝不覆盖已存在目标；实际结果写入同目录
 * {@code actual.json}（{@link TrashManifestItemDTO}）用于对账与补偿判断。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class TrashManifestDTO {
    private final int version;
    private final String targetType;
    private final Long targetId;
    private final Long taskId;
    private final Instant createdAt;
    private final List<Entry> entries;

    @JsonCreator
    public TrashManifestDTO(@JsonProperty("version") int version, @JsonProperty("targetType") String targetType,
                            @JsonProperty("targetId") Long targetId, @JsonProperty("taskId") Long taskId,
                            @JsonProperty("createdAt") Instant createdAt,
                            @JsonProperty("entries") List<Entry> entries) {
        this.version = version;
        this.targetType = targetType;
        this.targetId = targetId;
        this.taskId = taskId;
        this.createdAt = createdAt;
        this.entries = entries;
    }

    public int version() { return version; }
    public String targetType() { return targetType; }
    public Long targetId() { return targetId; }
    public Long taskId() { return taskId; }
    public Instant createdAt() { return createdAt; }
    public List<Entry> entries() { return entries; }

    public static final int CURRENT_VERSION = 1;

    /**
     * 单个资产条目。
     *
     * @param rootKey           源存储根（HQ/LQ/THUMBS/METADATA/STAGING）
     * @param sourceRelativePath 相对源存储根的路径
     * @param trashRelativePath  相对清单目录（TRASH/{targetType}/{targetId}/{taskId}/）的目标路径
     */
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    public static final class Entry {
        private final String rootKey;
        private final String sourceRelativePath;
        private final String trashRelativePath;

        @JsonCreator
        public Entry(@JsonProperty("rootKey") String rootKey,
                     @JsonProperty("sourceRelativePath") String sourceRelativePath,
                     @JsonProperty("trashRelativePath") String trashRelativePath) {
            this.rootKey = rootKey;
            this.sourceRelativePath = sourceRelativePath;
            this.trashRelativePath = trashRelativePath;
        }

        public String rootKey() { return rootKey; }
        public String sourceRelativePath() { return sourceRelativePath; }
        public String trashRelativePath() { return trashRelativePath; }
    }
}
