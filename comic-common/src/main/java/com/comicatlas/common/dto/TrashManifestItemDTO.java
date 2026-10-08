package com.comicatlas.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

/**
 * TRASH 实际执行结果（Worker 写入，可变）。
 * <p>
 * 存放于 {@code TRASH/{targetType}/{targetId}/{taskId}/actual.json}。
 * status 取值：
 * <ul>
 *   <li>TRASHED      — 全部条目已移入 TRASH（命令成功）</li>
 *   <li>COMPENSATED  — 部分移动失败，已全部回滚到源位置（命令失败，实体应回 READY）</li>
 *   <li>PARTIAL      — 部分移动失败且回滚不完整，文件部分在 TRASH（实体保持 TRASHING，仅 RECONCILE/RETRY）</li>
 *   <li>RESTORED     — 恢复命令已把文件全部移回源位置</li>
 *   <li>PURGED       — 清理命令已删除清单文件</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class TrashManifestItemDTO {
    private final int version;
    private final String targetType;
    private final Long targetId;
    private final Long taskId;
    private final String status;
    private final String errorMessage;
    private final Instant completedAt;
    private final List<Entry> entries;

    @JsonCreator
    public TrashManifestItemDTO(@JsonProperty("version") int version,
                                @JsonProperty("targetType") String targetType,
                                @JsonProperty("targetId") Long targetId,
                                @JsonProperty("taskId") Long taskId,
                                @JsonProperty("status") String status,
                                @JsonProperty("errorMessage") String errorMessage,
                                @JsonProperty("completedAt") Instant completedAt,
                                @JsonProperty("entries") List<Entry> entries) {
        this.version = version;
        this.targetType = targetType;
        this.targetId = targetId;
        this.taskId = taskId;
        this.status = status;
        this.errorMessage = errorMessage;
        this.completedAt = completedAt;
        this.entries = entries;
    }

    public int version() { return version; }
    public String targetType() { return targetType; }
    public Long targetId() { return targetId; }
    public Long taskId() { return taskId; }
    public String status() { return status; }
    public String errorMessage() { return errorMessage; }
    public Instant completedAt() { return completedAt; }
    public List<Entry> entries() { return entries; }

    public static final int CURRENT_VERSION = 1;

    public static final String STATUS_TRASHED = "TRASHED";
    public static final String STATUS_COMPENSATED = "COMPENSATED";
    public static final String STATUS_PARTIAL = "PARTIAL";
    public static final String STATUS_RESTORED = "RESTORED";
    public static final String STATUS_PURGED = "PURGED";

    /** 每个条目的实际结果。 */
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    public static final class Entry {
        private final String rootKey;
        private final String sourceRelativePath;
        private final String trashRelativePath;
        private final String state;
        private final String detail;

        @JsonCreator
        public Entry(@JsonProperty("rootKey") String rootKey,
                     @JsonProperty("sourceRelativePath") String sourceRelativePath,
                     @JsonProperty("trashRelativePath") String trashRelativePath,
                     @JsonProperty("state") String state,
                     @JsonProperty("detail") String detail) {
            this.rootKey = rootKey;
            this.sourceRelativePath = sourceRelativePath;
            this.trashRelativePath = trashRelativePath;
            this.state = state;
            this.detail = detail;
        }

        public String rootKey() { return rootKey; }
        public String sourceRelativePath() { return sourceRelativePath; }
        public String trashRelativePath() { return trashRelativePath; }
        public String state() { return state; }
        public String detail() { return detail; }

        /** 已移入 TRASH */
        public static final String STATE_TRASHED = "TRASHED";
        /** 源文件缺失，跳过（视为成功） */
        public static final String STATE_MISSING = "MISSING";
        /** 仍在源位置（移动失败/已回滚） */
        public static final String STATE_SOURCE = "SOURCE";
    }
}
