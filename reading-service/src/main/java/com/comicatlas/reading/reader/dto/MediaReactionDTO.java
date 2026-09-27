package com.comicatlas.reading.reader.dto;

import com.comicatlas.contract.common.enums.MediaReaction;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

/** 当前媒体偏好标记响应。 */
@Data
@AllArgsConstructor
public class MediaReactionDTO {
    private Long pageId;
    private MediaReaction reaction;
    private LocalDateTime reactionAt;
}
