package com.comicatlas.api.media.dto;

import com.comicatlas.contract.common.enums.MediaReaction;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/** 漫画或章节标记批量修改请求。 */
@Data
public class ContentReactionBatchRequest {
    @NotEmpty
    private List<Long> ids;

    @NotNull
    private MediaReaction reaction;
}
