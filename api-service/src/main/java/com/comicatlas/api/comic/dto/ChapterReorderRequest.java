package com.comicatlas.api.comic.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

/**
 * 章节重排请求。
 */
@Data
public class ChapterReorderRequest {

    /** 全书中的目标位置（1 基） */
    @NotNull(message = "targetGlobalOrder 不能为空")
    @Positive(message = "targetGlobalOrder 必须为正数")
    private Integer targetGlobalOrder;
}
