package com.comicatlas.api.comic.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import java.util.List;

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
