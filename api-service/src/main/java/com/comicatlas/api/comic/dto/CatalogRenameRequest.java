package com.comicatlas.api.comic.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 重命名目录请求。
 */
@Data
public class CatalogRenameRequest {

    /** 新目录标题（必填） */
    @NotBlank(message = "目录标题不能为空")
    @Size(max = 255, message = "目录标题长度不能超过255")
    private String title;
}
