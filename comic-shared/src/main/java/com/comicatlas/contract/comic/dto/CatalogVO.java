package com.comicatlas.contract.comic.dto;

import lombok.Data;

/** 目录视图对象 */
@Data
public class CatalogVO {
    private Long id;
    private Long comicId;
    private Long parentId;
    private String title;
    private Integer sortOrder;

}
