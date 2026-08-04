package com.comicatlas.api.upload.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 会话状态响应（断点续传查询）。
 */
@Data
public class UploadSessionStatusResponse {
    private String sessionId;
    private String status;
    private long totalBytes;
    private int totalFiles;
    private LocalDateTime expiresAt;
    private LocalDateTime completedAt;
    private List<UploadFileResponse> files;
}
