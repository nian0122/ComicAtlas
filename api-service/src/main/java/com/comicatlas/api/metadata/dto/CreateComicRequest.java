package com.comicatlas.api.metadata.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 创建空漫画（DRAFT）请求。
 * <p>
 * 标题必填；分类、标签可选。创建后漫画处于 DRAFT 生命周期，
 * 可通过导入或上传入口补充内容。
 */
@Data
public class CreateComicRequest {

    /** 漫画标题（必填） */
    @NotBlank(message = "标题不能为空")
    @Size(max = 255, message = "标题长度不能超过255")
    private String title;

    /** 日文标题（可选） */
    @Size(max = 255, message = "日文标题长度不能超过255")
    private String titleJpn;

    /** 作者（可选） */
    @Size(max = 128, message = "作者长度不能超过128")
    private String author;

    /** 简介（可选） */
    @Size(max = 4000, message = "描述长度不能超过4000")
    private String description;

    /** 分类 ID（可选） */
    private Long categoryId;

    /** 标签 ID 列表（可选） */
    private List<Long> tagIds;
}
