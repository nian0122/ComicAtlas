package com.comicatlas.api.management.dto;

import lombok.Data;

import java.time.LocalDateTime;

/** 回收站中的漫画、章节或媒体条目。 */
@Data
public class TrashContentVO {
    private String targetType;
    private Long targetId;
    private Long comicId;
    private Long chapterId;
    private String title;
    private String subtitle;
    private String coverUrl;
    private String status;
    private String mediaType;
    private Integer pageNumber;
    private LocalDateTime createdAt;
    private LocalDateTime trashedAt;
}
