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
public class BatchComicUpdateDTO {

    @NotEmpty(message = "漫画ID列表不能为空")
    @Size(max = 100, message = "单次最多更新100部漫画")
    private List<Long> comicIds;

    private Long categoryId;

    private List<Long> addTagIds;
}
