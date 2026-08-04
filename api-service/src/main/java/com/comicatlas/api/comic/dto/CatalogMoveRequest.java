package com.comicatlas.api.comic.dto;

import lombok.Data;

/** 移动目录请求 */
@Data
public class CatalogMoveRequest {
    /** 新父目录 ID，null 表示移动到根 */
    private Long parentId;
}
