package com.comicatlas.api.storage.dto;

import lombok.Data;

@Data
public class ComicStorageQuery {
    private String hqStatus;
    private String lqStatus;
    private String sort;
    private String order;
    private String keyword;
    private String category;
    private String tag;
}
