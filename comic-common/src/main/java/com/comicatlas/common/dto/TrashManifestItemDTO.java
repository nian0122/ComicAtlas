package com.comicatlas.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

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
public record TrashManifestItemDTO(
    int version,
    String targetType,
    Long targetId,
    Long taskId,
    String status,
    String errorMessage,
    Instant completedAt,
    List<Entry> entries
) {

    public static final int CURRENT_VERSION = 1;

    public static final String STATUS_TRASHED = "TRASHED";
    public static final String STATUS_COMPENSATED = "COMPENSATED";
    public static final String STATUS_PARTIAL = "PARTIAL";
    public static final String STATUS_RESTORED = "RESTORED";
    public static final String STATUS_PURGED = "PURGED";

    /** 每个条目的实际结果。 */
    public record Entry(
        String rootKey,
        String sourceRelativePath,
        String trashRelativePath,
        String state,
        String detail
    ) {

        /** 已移入 TRASH */
        public static final String STATE_TRASHED = "TRASHED";
        /** 源文件缺失，跳过（视为成功） */
        public static final String STATE_MISSING = "MISSING";
        /** 仍在源位置（移动失败/已回滚） */
        public static final String STATE_SOURCE = "SOURCE";
    }
}
