package com.comicatlas.api.reader.dto;

import lombok.Data;

@Data
public class HistoryUpdateRequest {
    private Long chapterId;
    private Integer pageNumber;
}
