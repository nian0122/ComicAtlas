package com.comicatlas.api.comic.dto;

import com.comicatlas.api.comic.entity.Chapter;
import lombok.Data;

/** 章节视图对象 */
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

    public static ChapterVO from(Chapter ch) {
        ChapterVO vo = new ChapterVO();
        vo.setId(ch.getId());
        vo.setComicId(ch.getComicId());
        vo.setCatalogId(ch.getCatalogId());
        vo.setTitle(ch.getTitle());
        vo.setChapterNo(ch.getChapterNo());
        vo.setPageCount(ch.getPageCount());
        vo.setSortOrder(ch.getSortOrder());
        vo.setGlobalOrder(ch.getGlobalOrder());
        vo.setStatus(ch.getStatus());
        return vo;
    }
}
