package com.comicatlas.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

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
public record TrashManifestDTO(
    int version,
    String targetType,
    Long targetId,
    Long taskId,
    Instant createdAt,
    List<Entry> entries
) {

    public static final int CURRENT_VERSION = 1;

    /**
     * 单个资产条目。
     *
     * @param rootKey           源存储根（HQ/LQ/THUMBS/METADATA/STAGING）
     * @param sourceRelativePath 相对源存储根的路径
     * @param trashRelativePath  相对清单目录（TRASH/{targetType}/{targetId}/{taskId}/）的目标路径
     */
    public record Entry(
        String rootKey,
        String sourceRelativePath,
        String trashRelativePath
    ) {
    }
}
