package com.comicatlas.api.admin.dto;

import java.time.LocalDateTime;

/**
 * 元数据刷新结果。
 */
public record RefreshMetadataResult(Long comicId, String status, int catalogs, int chapters, int pages,
                                    long durationMs, LocalDateTime refreshedAt) {
}
