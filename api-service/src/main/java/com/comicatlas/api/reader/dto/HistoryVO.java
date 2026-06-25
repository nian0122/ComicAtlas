package com.comicatlas.api.reader.dto;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class HistoryVO {
    private Long comicId;
    private String comicTitle;
    private String coverUrl;
    private Long chapterId;
    private String chapterNo;
    private Integer pageNumber;
    private Integer totalPages;
    private Integer progressPercent;
    private LocalDateTime updatedAt;
}
