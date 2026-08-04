package com.comicatlas.api.upload.dto;

import lombok.Data;

/**
 * 会话内单个文件状态。
 */
@Data
public class UploadFileResponse {
    private String fileId;
    private String storageName;
    private long receivedBytes;
    private long sizeBytes;
    private boolean complete;
    private String receivedRanges;
}
