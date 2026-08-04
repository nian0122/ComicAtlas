package com.comicatlas.api.upload.dto;

import lombok.Data;

import java.util.List;

/**
 * complete 响应 — 已创建的管理任务与预建 STAGING 媒体。
 */
@Data
public class UploadCompleteResponse {
    private Long taskId;
    private String taskType;
    private String status;
    private Integer itemCount;
    private List<Long> mediaIds;
}
