package com.comicatlas.api.storage.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.comicatlas.api.storage.enums.SnapshotRefreshStatus;
import java.time.LocalDateTime;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class StorageStatsDTO {
    private long hqBytes;
    private long lqBytes;
    private long thumbBytes;
    private int comicCount;

    /** 缩略图首次统计完成之前，合计不可视作完整容量。 */
    @JsonProperty("snapshotAvailable")
    private boolean hasSnapshot;
    private long thumbFileCount;
    private LocalDateTime thumbUpdatedAt;
    private SnapshotRefreshStatus refreshStatus;

    public Long getTotalBytes() {
        return hasSnapshot ? hqBytes + lqBytes + thumbBytes : null;
    }
}
