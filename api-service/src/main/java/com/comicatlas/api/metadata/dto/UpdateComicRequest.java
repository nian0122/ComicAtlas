package com.comicatlas.api.metadata.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 更新漫画请求 — 使用 version 乐观锁。
 * <p>
 * version 必填；title/author/description/categoryId 可选（null 表示不修改）。
 * 并发更新同一版本时，后提交者收到 409 冲突。
 */
@Data
public class UpdateComicRequest {

    /** 乐观锁版本号（必填，来自上次读到的 version） */
    @NotNull(message = "缺少 version")
    private Integer version;

    /** 标题（可选，null 不修改） */
    @Size(max = 255, message = "标题长度不能超过255")
    private String title;

    /** 日文标题（可选，null 不修改） */
    @Size(max = 255, message = "日文标题长度不能超过255")
    private String titleJpn;

    /** 作者（可选，null 不修改） */
    @Size(max = 128, message = "作者长度不能超过128")
    private String author;

    /** 简介（可选，null 不修改） */
    @Size(max = 4000, message = "描述长度不能超过4000")
    private String description;

    /** 分类 ID（可选，null 不修改） */
    private Long categoryId;
}
