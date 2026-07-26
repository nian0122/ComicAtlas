package com.comicatlas.common.event;

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
