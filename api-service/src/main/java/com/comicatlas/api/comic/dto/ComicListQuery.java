package com.comicatlas.api.comic.dto;

import lombok.Data;

import java.util.List;

@Data
public class ComicListQuery {
    private String keyword;
    private String tag;
    private List<String> tags;
    private String tagMode = "OR";
    private String status;
    private String category;
    private String sourceType;
    private String sort = "createdAt";
    private Integer page = 1;
    private Integer size = 20;
}
