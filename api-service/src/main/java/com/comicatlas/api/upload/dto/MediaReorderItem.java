package com.comicatlas.api.upload.dto;

import lombok.Data;

/**
 * 媒体重排结果项。
 */
@Data
public class MediaReorderItem {
    private Long mediaId;
    private Integer pageNumber;
}
