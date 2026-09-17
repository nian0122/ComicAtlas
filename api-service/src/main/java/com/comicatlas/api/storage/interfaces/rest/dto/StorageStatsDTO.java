package com.comicatlas.api.storage.interfaces.rest.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class StorageStatsDTO {
    private long hqBytes;
    private long lqBytes;
    private long thumbBytes;
    private int comicCount;

    public long getTotalBytes() {
        return hqBytes + lqBytes + thumbBytes;
    }
}
