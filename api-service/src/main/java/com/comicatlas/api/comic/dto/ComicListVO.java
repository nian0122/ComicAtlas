package com.comicatlas.api.comic.dto;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class ComicListVO {
    private Long id;
    private String title;
    private String author;
    private String coverUrl;
    private Integer pageCount;
    private Long categoryId;
    private String categoryName;
    private String status;
    private String lqStatus;
    private Integer progressPercent;
    private Long lastReadChapterId;
    private Integer lastReadPage;
    private LocalDateTime createdAt;
}
