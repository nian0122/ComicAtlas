package com.comicatlas.api.comic.dto;

import lombok.Data;

/** 重命名章节请求（字段可选，至少提供一个） */
@Data
public class ChapterRenameRequest {
    private String title;
    private String chapterNo;
}
