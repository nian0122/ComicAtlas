package com.comicatlas.api.management.batch.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 批量选择判别联合：IDS 显式列表或 FILTER 筛选条件 + 排除项。
 * <p>
 * JSON 判别字段为 {@code type}：{@code "IDS"} 或 {@code "FILTER"}。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = BatchSelection.Ids.class, name = "IDS"),
    @JsonSubTypes.Type(value = BatchSelection.Filter.class, name = "FILTER")
})
public abstract class BatchSelection {

    @Data
    @EqualsAndHashCode(callSuper = false)
    public static class Ids extends BatchSelection {
        @NotEmpty(message = "ids 不能为空")
        private java.util.List<Long> ids;
    }

    @Data
    @EqualsAndHashCode(callSuper = false)
    public static class Filter extends BatchSelection {
        private com.comicatlas.api.comic.dto.ComicListQuery query;
        private java.util.List<Long> excludedIds;
    }
}
