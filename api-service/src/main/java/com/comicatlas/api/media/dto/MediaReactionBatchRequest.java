package com.comicatlas.api.media.dto;

import com.comicatlas.contract.common.enums.MediaReaction;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/** 管理端批量修改媒体标记请求。 */
@Data
public class MediaReactionBatchRequest {
    @NotEmpty
    private List<Long> mediaIds;
    @NotNull
    private MediaReaction reaction;
}
