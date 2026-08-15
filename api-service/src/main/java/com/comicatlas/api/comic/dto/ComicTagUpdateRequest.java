package com.comicatlas.api.comic.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 全量覆盖漫画标签绑定关系请求。
 * <p>
 * tagIds 为空数组表示清空标签；null 语义等同清空（保持兼容）。
 */
@Data
public class ComicTagUpdateRequest {

    /** 新的标签 ID 集合（全量覆盖） */
    @Size(max = 100, message = "单次最多绑定100个标签")
    private List<Long> tagIds;
}
