package com.comicatlas.common.event.payload;

import java.math.BigDecimal;

public record VideoMetadataFixResult(
    Long pageId,
    Integer width,
    Integer height,
    BigDecimal duration,
    String container,
    String videoCodec,
    String audioCodec
) {
}
