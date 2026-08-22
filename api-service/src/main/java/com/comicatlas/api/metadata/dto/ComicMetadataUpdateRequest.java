package com.comicatlas.api.metadata.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 更新漫画元数据请求（全量覆盖标题/作者/描述）。
 * <p>
 * 标题必填，禁止将标题清空；分类可选，null 表示不修改分类。
 */
@Data
public class ComicMetadataUpdateRequest {

    /** 漫画标题（必填） */
    @NotBlank(message = "标题不能为空")
    @Size(max = 255, message = "标题长度不能超过255")
    private String title;

    /** 作者（可选） */
    @Size(max = 128, message = "作者长度不能超过128")
    private String author;

    /** 简介（可选） */
    @Size(max = 4000, message = "描述长度不能超过4000")
    private String description;

    /** 分类 ID（可选，null 不修改） */
    private Long categoryId;
}
