package com.comicatlas.contract.comic.dto;

import lombok.Data;

import java.util.List;

@Data
public class ComicListQuery {
    private String keyword;
    private String tag;
    private List<String> tags;
    /** 标签筛选模式：OR 任一、AND 全部、NOT 排除所选标签。 */
    private String tagMode = "OR";
    private String status;
    private String category;
    private String sourceType;
    private String sort = "createdAt";
    private String order = "desc";
    private Integer page = 1;
    private Integer size = 20;
}
