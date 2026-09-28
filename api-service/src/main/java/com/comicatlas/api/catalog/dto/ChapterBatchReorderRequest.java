package com.comicatlas.api.catalog.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/** 全书批量章节重排请求。 */
@Data
public class ChapterBatchReorderRequest {

    /** 按目标全局顺序排列的完整章节 ID 列表。 */
    @NotEmpty(message = "chapterIds 不能为空")
    @Size(max = 10000, message = "章节数量不能超过 10000")
    private List<@NotNull @Positive Long> chapterIds;
}
