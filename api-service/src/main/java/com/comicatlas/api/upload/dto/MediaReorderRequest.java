package com.comicatlas.api.upload.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * 章节媒体重排请求 — 提供完整的媒体顺序，服务端重新分配连续 pageNumber。
 */
@Data
public class MediaReorderRequest {
    @NotEmpty
    private List<Long> mediaIds;
}
