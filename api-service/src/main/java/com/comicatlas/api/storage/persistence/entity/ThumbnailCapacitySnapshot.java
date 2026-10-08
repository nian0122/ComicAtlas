package com.comicatlas.api.storage.persistence.entity;

import com.comicatlas.api.storage.enums.SnapshotRefreshStatus;
import lombok.Data;
import java.time.LocalDateTime;

/** 持久化的最近成功容量，以及尚未完成的刷新版本。 */
@Data
public class ThumbnailCapacitySnapshot {
    private String rootKey;
    private String rootFingerprint;
    private long totalBytes;
    private long fileCount;
    private LocalDateTime scannedAt;
    private LocalDateTime attemptedAt;
    private LocalDateTime requestedAt;
    private long requestedVersion;
    private long completedVersion;
    private SnapshotRefreshStatus refreshStatus;
}
