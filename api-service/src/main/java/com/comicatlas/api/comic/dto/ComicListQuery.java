package com.comicatlas.api.comic.dto;

import lombok.Data;

@Data
public class ComicListQuery {
    private String keyword;
    private String tag;
    private String status;
    private String category;
    private String sourceType;
    private String sort = "createdAt";
    private Integer page = 1;
    private Integer size = 20;
}
