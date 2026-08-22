package com.comicatlas.api.task.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import java.util.List;

/** 批量更新结果视图（管理端） */
@Data
public class BatchUpdateResultVO {
    private int total;
    private int succeeded;
    private List<FailedItem> failed;

    @Data
    @AllArgsConstructor
    public static class FailedItem {
        private Long comicId;
        private String title;
        private String reason;
    }
}
