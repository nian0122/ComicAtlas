package com.comicatlas.api.comic.dto;

import lombok.Data;

@Data
public class CategoryDTO {
    private Long id;
    private String name;
    private Integer sortOrder;
}
