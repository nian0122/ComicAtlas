package com.comicatlas.api.media.dto;

import com.comicatlas.contract.common.enums.MediaReaction;
import lombok.Data;

import java.time.Instant;

/** 管理端漫画或章节标记视图。 */
@Data
public class ContentReactionVO {
    private Long id;
    private String targetType;
    private Long comicId;
    private String title;
    private MediaReaction reaction;
    private Instant reactionAt;
    private String status;
}
