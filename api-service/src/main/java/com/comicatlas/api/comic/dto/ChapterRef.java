package com.comicatlas.api.comic.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ChapterRef {
    private Long id;
    private String chapterNo;
    private String title;
    private int globalOrder;
    private int pageCount;
    private String status;
}
