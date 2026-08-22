package com.comicatlas.api.catalog.dto;

import lombok.Data;

/** 章节视图对象（管理端） */
@Data
public class ChapterVO {
    private Long id;
    private Long comicId;
    private Long catalogId;
    private String title;
    private String chapterNo;
    private Integer pageCount;
    private Integer sortOrder;
    private Integer globalOrder;
    private String status;

}
