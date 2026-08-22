package com.comicatlas.api.catalog.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 创建章节请求。
 */
@Data
public class ChapterCreateRequest {

    /** 章节标题（必填） */
    @NotBlank(message = "章节标题不能为空")
    @Size(max = 255, message = "章节标题长度不能超过255")
    private String title;

    /** 原始编号（目录内唯一），缺省 "1" */
    @Size(max = 32, message = "章节编号长度不能超过32")
    private String chapterNo;

    /** 所属目录 ID，null 表示根级 */
    private Long catalogId;
}
