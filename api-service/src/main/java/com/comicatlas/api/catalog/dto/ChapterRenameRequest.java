package com.comicatlas.api.catalog.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 重命名章节请求（字段可选，至少提供一个）。
 */
@Data
public class ChapterRenameRequest {

    /** 新章节标题（可选） */
    @Size(max = 255, message = "章节标题长度不能超过255")
    private String title;

    /** 新原始编号（可选，目录内唯一） */
    @Size(max = 32, message = "章节编号长度不能超过32")
    private String chapterNo;
}
