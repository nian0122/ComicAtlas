package com.comicatlas.api.upload.interfaces.rest.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 创建上传会话响应 — 返回 opaque sessionId、分片大小与每个文件的存储名。
 */
@Data
public class CreateUploadSessionResponse {
    private String sessionId;
    private long chunkSize;
    private LocalDateTime expiresAt;
    private long totalBytes;
    private List<UploadFileResponse> files;
}
