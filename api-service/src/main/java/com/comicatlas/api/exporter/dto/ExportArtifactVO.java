package com.comicatlas.api.exporter.dto;

import lombok.Data;

/**
 * 导出产物分卷视图对象 — 仅承载元数据，不承载任何文件字节。
 * <p>
 * index 为 1-based 卷序号；lastSegment 标记是否最后一个 {@code .zip} 卷；
 * physicalPath 为本地物理路径（供本机用户定位文件，不允许进入 HTTP 日志）。
 */
@Data
public class ExportArtifactVO {
    /** 1-based 卷序号 */
    private Integer index;
    /** 分卷文件名（如 base.z01 / base.zip） */
    private String fileName;
    /** 分卷大小（字节） */
    private Long size;
    /** 是否最后一个 .zip 卷 */
    private Boolean lastSegment;
    /** 本地物理路径 */
    private String physicalPath;
}
