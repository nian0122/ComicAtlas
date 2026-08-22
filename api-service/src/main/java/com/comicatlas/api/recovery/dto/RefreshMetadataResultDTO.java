package com.comicatlas.api.recovery.dto;

import java.time.LocalDateTime;

/**
 * 元数据刷新结果。
 */
public record RefreshMetadataResultDTO(Long comicId, String status, int catalogs, int chapters, int pages,
                                    long durationMs, LocalDateTime refreshedAt) {
}
