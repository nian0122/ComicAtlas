package com.comicatlas.contract.reader.dto;

import lombok.Data;

@Data
public class HistoryUpdateRequest {
    private Long chapterId;
    private Integer pageNumber;
}
