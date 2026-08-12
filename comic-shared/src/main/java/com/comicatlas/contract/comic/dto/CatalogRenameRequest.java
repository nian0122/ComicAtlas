package com.comicatlas.contract.comic.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 重命名目录请求 */
@Data
public class CatalogRenameRequest {
    @NotBlank(message = "目录标题不能为空")
    private String title;
}
