package com.comicatlas.api.comic.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 创建章节请求 */
@Data
public class ChapterCreateRequest {
    @NotBlank(message = "章节标题不能为空")
    private String title;
    /** 原始编号（目录内唯一），缺省 "1" */
    private String chapterNo;
    /** 所属目录 ID，null 表示根级 */
    private Long catalogId;
}
