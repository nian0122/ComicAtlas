package com.comicatlas.api.media.dto;

import com.comicatlas.contract.common.enums.MediaReaction;
import lombok.Data;

import java.time.LocalDateTime;

/** 管理端媒体标记视图。 */
@Data
public class MediaReactionVO {
    private Long id;
    private Long chapterId;
    private Integer pageNumber;
    private String mediaType;
    private MediaReaction reaction;
    private LocalDateTime reactionAt;
    private String status;
}
