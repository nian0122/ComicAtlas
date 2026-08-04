package com.comicatlas.api.upload.dto;

import lombok.Data;

/**
 * 分片写入响应 — 客户端据此续传/重传。
 */
@Data
public class UploadChunkResponse {
    private String fileId;
    private long receivedBytes;
    private boolean complete;
    private String receivedRanges;
}
