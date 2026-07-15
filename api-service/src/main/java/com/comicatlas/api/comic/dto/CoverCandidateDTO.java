package com.comicatlas.api.comic.dto;

import lombok.Data;

@Data
public class CoverCandidateDTO {
    private Long pageId;
    private Long chapterId;
    private String chapterTitle;
    private Integer pageNumber;
    private String url;
}
