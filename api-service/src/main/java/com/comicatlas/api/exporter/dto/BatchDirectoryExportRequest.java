package com.comicatlas.api.exporter.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/** 批量文件夹导出的漫画选择。 */
@Data
public class BatchDirectoryExportRequest {
    @NotEmpty
    @Size(max = 500)
    private List<@NotNull Long> comicIds;
}
