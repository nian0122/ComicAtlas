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

    public static ChapterVO from(Chapter chapter) {
        ChapterVO vo = new ChapterVO();
        vo.setId(chapter.getId());
        vo.setComicId(chapter.getComicId());
        vo.setCatalogId(chapter.getCatalogId());
        vo.setTitle(chapter.getTitle());
        vo.setChapterNo(chapter.getChapterNo());
        vo.setPageCount(chapter.getPageCount());
        vo.setSortOrder(chapter.getSortOrder());
        vo.setGlobalOrder(chapter.getGlobalOrder());
        vo.setStatus(chapter.getStatus());
        return vo;
    }
}
