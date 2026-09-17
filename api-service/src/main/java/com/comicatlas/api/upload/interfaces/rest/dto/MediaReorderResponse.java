package com.comicatlas.api.upload.interfaces.rest.dto;

import lombok.Data;

import java.util.List;

/**
 * 媒体重排结果。
 */
@Data
public class MediaReorderResponse {
    private List<MediaReorderItem> items;
}
