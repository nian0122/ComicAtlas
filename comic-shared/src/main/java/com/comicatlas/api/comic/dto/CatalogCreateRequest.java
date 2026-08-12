package com.comicatlas.api.comic.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 创建目录请求 */
@Data
public class CatalogCreateRequest {
    @NotBlank(message = "目录标题不能为空")
    private String title;
    /** 父目录 ID，null 表示根目录 */
    private Long parentId;
    /** 可选：显式 sort_order，缺省自动追加到同级末尾 */
    private Integer sortOrder;
}
