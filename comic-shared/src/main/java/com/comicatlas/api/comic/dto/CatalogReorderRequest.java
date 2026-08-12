package com.comicatlas.api.comic.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 目录重排请求 */
@Data
public class CatalogReorderRequest {
    /** 同级中的目标位置（1 基） */
    @NotNull(message = "sortOrder 不能为空")
    private Integer sortOrder;
}
