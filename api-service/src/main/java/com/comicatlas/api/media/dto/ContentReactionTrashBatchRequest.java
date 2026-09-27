package com.comicatlas.api.media.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/** 漫画或章节批量回收请求。 */
@Data
public class ContentReactionTrashBatchRequest {
    @NotEmpty
    private List<Long> ids;
}
