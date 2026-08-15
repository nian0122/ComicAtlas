package com.comicatlas.api.comic.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 批量更新漫画的 category 和 tag。
 * Controller 层手动校验 categoryId 和 addTagIds 不能同时为空。
 */
@Data
public class BatchComicUpdateRequest {

    /** 待更新的漫画 ID 列表 */
    @NotEmpty(message = "漫画ID列表不能为空")
    @Size(max = 100, message = "单次最多更新100部漫画")
    private List<Long> comicIds;

    /** 目标分类 ID（可选） */
    private Long categoryId;

    /** 需要新增的标签 ID 列表（可选） */
    private List<Long> addTagIds;
}
