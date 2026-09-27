package com.comicatlas.reading.reader.dto;

import com.comicatlas.contract.common.enums.MediaReaction;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 阅读端媒体偏好标记请求。 */
@Data
public class MediaReactionRequest {
    @NotNull
    private MediaReaction reaction;
}
