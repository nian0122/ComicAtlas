package com.comicatlas.api.admin.dto;

import lombok.Data;

@Data
public class ComicStorageQuery {
    private String hqStatus;
    private String lqStatus;
    private String sort;
    private String order;
    private String keyword;
}
