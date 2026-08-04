package com.comicatlas.api.comic.dto;

import com.comicatlas.api.comic.entity.Catalog;
import lombok.Data;

/** 目录视图对象 */
@Data
public class CatalogVO {
    private Long id;
    private Long comicId;
    private Long parentId;
    private String title;
    private Integer sortOrder;

    public static CatalogVO from(Catalog cat) {
        CatalogVO vo = new CatalogVO();
        vo.setId(cat.getId());
        vo.setComicId(cat.getComicId());
        vo.setParentId(cat.getParentId());
        vo.setTitle(cat.getTitle());
        vo.setSortOrder(cat.getSortOrder());
        return vo;
    }
}
