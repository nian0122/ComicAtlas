package com.comicatlas.api.comic.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChapterRef {
    private Long id;
    private String chapterNo;
    private String title;
    private int globalOrder;
    private int pageCount;
    private String status;
}
