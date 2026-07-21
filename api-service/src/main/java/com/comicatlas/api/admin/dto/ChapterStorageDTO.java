package com.comicatlas.api.admin.dto;

import lombok.Data;

@Data
public class ChapterStorageDTO {
    private Long chapterId;
    private String chapterNo;
    private String title;
    private Integer pageCount;
    private Long hqSize;
    private Long lqSize;
    private String hqStatus;
    private String lqStatus;
}
