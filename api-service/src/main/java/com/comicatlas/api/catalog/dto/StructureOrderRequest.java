package com.comicatlas.api.catalog.dto;

import com.comicatlas.api.catalog.enums.StructureNodeType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/** 同一父目录下目录与章节的完整展示顺序。 */
@Data
public class StructureOrderRequest {
    private Long parentCatalogId;

    @NotEmpty(message = "同级节点顺序不能为空")
    @Valid
    private List<StructureOrderItem> items;

    @Data
    public static class StructureOrderItem {
        @NotNull(message = "节点类型不能为空")
        private StructureNodeType type;

        @NotNull(message = "节点 ID 不能为空")
        private Long id;
    }
}
