package com.comicatlas.api.task.batch.dto;

import lombok.Data;

import java.util.List;

/**
 * 批量操作负载。
 * <p>
 * METADATA_UPDATE：categoryId + addTagIds；
 * 其他操作（LQ/HQ/TRANSCODE/回收/恢复/清理）负载为空。
 */
@Data
public class BatchOperationPayloadDTO {

    private Long categoryId;

    private List<Long> addTagIds;

    private String title;

    private String author;

    private String description;
}
