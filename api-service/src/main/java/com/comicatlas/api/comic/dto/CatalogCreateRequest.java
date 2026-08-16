package com.comicatlas.api.comic.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 创建目录请求。
 */
@Data
public class CatalogCreateRequest {

    /** 目录标题（必填） */
    @NotBlank(message = "目录标题不能为空")
    @Size(max = 255, message = "目录标题长度不能超过255")
    private String title;

    /** 父目录 ID，null 表示根目录 */
    private Long parentId;

    /** 可选：显式 sort_order，缺省自动追加到同级末尾 */
    @Positive(message = "sortOrder 必须为正数")
    private Integer sortOrder;
}
