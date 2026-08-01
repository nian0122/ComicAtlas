package com.comicatlas.api.admin.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

@Data
public class StorageStatsDTO {
    private long hqBytes;
    private long lqBytes;
    private long thumbBytes;
    private int comicCount;

    @JsonIgnore
    public long getTotalBytes() {
        return hqBytes + lqBytes + thumbBytes;
    }
}
