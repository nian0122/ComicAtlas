package com.comicatlas.reading.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 章节引用（阅读端，目录树/阅读导航用） */
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
