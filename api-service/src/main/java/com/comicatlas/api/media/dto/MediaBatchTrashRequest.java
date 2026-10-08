package com.comicatlas.api.media.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/** 按标记批量将媒体送入回收站。 */
@Data
public class MediaBatchTrashRequest {
    @NotEmpty
    private List<Long> mediaIds;
}
