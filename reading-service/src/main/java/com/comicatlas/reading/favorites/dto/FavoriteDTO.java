package com.comicatlas.reading.favorites.dto;

import java.math.BigDecimal;
import java.time.Instant;

/** 阅读端喜欢卡片，三个层级的标记保持独立。 */
public record FavoriteDTO(Long id, String targetType, Long comicId, String comicTitle,
        Long chapterId, String title, Integer pageNumber, String mediaType, BigDecimal duration,
        String coverUrl, String previewUrl, Instant reactionAt, Long lastReadChapterId,
        Integer lastReadPageNumber) {
}
