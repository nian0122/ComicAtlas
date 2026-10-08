package com.comicatlas.reading.reader.dto;

import com.comicatlas.contract.common.enums.MediaReaction;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.Instant;

/** 漫画或章节标记响应。 */
@Data
@AllArgsConstructor
public class ReactionDTO {
    private Long targetId;
    private MediaReaction reaction;
    private Instant reactionAt;
}
