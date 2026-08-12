package com.comicatlas.contract.comic.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 章节重排请求 */
@Data
public class ChapterReorderRequest {
    /** 全书中的目标位置（1 基） */
    @NotNull(message = "targetGlobalOrder 不能为空")
    private Integer targetGlobalOrder;
}
