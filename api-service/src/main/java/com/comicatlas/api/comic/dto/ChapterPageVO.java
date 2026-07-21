package com.comicatlas.api.comic.dto;

import lombok.Data;
import java.util.List;

@Data
public class ChapterPageVO {
    private Long comicId;
    private Long chapterId;
    private String chapterNo;
    private String chapterTitle;
    private List<MediaItemInfo> pages;
    private Integer total;
    private Long prevChapterId;
    private Long nextChapterId;
}
