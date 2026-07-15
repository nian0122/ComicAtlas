package com.comicatlas.api.comic.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CoverUpdateDTO {
    @NotNull(message = "封面页 ID 不能为空")
    private Long pageId;
}
