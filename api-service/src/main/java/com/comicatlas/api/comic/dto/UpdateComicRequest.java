package com.comicatlas.api.comic.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 更新漫画请求 — 使用 version 乐观锁，一次全量保存漫画基本信息和标签。
 * <p>
 * version/title/tagIds 必填；titleJpn/author/description/categoryId 可选。
 * tagIds 为全量替换语义（空列表清空标签），最多 100 个。
 * 并发更新同一版本时，后提交者收到 409 冲突。
 */
@Data
public class UpdateComicRequest {

    /** 乐观锁版本号（必填，来自上次读到的 version） */
    @NotNull(message = "缺少 version")
    private Integer version;

    /** 标题（必填，trim 后不能为空） */
    @NotBlank(message = "标题不能为空")
    @Size(max = 255, message = "标题长度不能超过255")
    private String title;

    /** 日文标题（可选，空白归一化为 null） */
    @Size(max = 255, message = "日文标题长度不能超过255")
    private String titleJpn;

    /** 作者（可选，空白归一化为 null） */
    @Size(max = 128, message = "作者长度不能超过128")
    private String author;

    /** 简介（可选，空白归一化为 null） */
    @Size(max = 4000, message = "描述长度不能超过4000")
    private String description;

    /** 分类 ID（可选，null 清除分类） */
    private Long categoryId;

    /** 标签 ID 全量替换列表（必填，空列表清空标签，最多 100 个，元素为正数） */
    @NotNull(message = "缺少 tagIds")
    @Size(max = 100, message = "标签数量不能超过100")
    private List<Long> tagIds;
}
