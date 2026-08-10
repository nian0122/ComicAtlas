package com.comicatlas.api.admin.dto;

import lombok.Data;

/**
 * 存储统计汇总（管理端存储概览）。
 * <p>
 * totalBytes 由 hqBytes + lqBytes + thumbBytes 计算得出，必须随 JSON 序列化，
 * 供前端"总大小"展示（曾因 @JsonIgnore 恒为 0 B，见审核 F9-02）。
 */
@Data
public class StorageStatsDTO {
    private long hqBytes;
    private long lqBytes;
    private long thumbBytes;
    private int comicCount;

    /** 总占用字节数 = HQ + LQ + 缩略图。 */
    public long getTotalBytes() {
        return hqBytes + lqBytes + thumbBytes;
    }
}
