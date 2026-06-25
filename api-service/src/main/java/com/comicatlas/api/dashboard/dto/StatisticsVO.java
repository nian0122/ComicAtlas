package com.comicatlas.api.dashboard.dto;

import lombok.Data;

@Data
public class StatisticsVO {
    private Long comicCount;
    private Long pageCount;
    private Long tagCount;
    private Long todayImported;
    private Long storageUsed;
    private Long importSuccessCount;
    private Long importFailedCount;
    private Double successRate;
}
