package com.comicatlas.reading.history.dto;

import lombok.Data;

/**
 * 阅读进度更新请求（阅读域，唯一写操作）。
 */
@Data
public class HistoryUpdateRequest {
    private Long chapterId;
    private Integer pageNumber;
}
