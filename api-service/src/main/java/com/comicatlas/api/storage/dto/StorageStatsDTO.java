package com.comicatlas.api.storage.dto;

import lombok.Data;

@Data
public class StorageStatsDTO {
    private long hqBytes;
    private long lqBytes;
    private long thumbBytes;
    private int comicCount;

    public long getTotalBytes() {
        return hqBytes + lqBytes + thumbBytes;
    }
}
