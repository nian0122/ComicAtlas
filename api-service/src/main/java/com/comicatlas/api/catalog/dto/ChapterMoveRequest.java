package com.comicatlas.api.catalog.dto;

import lombok.Data;

/**
 * 移动章节请求。
 */
@Data
public class ChapterMoveRequest {

    /** 目标目录 ID，null 表示移动到根级 */
    private Long catalogId;
}
