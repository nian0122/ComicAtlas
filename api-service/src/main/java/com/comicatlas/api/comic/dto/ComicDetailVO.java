package com.comicatlas.api.comic.dto;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class ComicDetailVO {
    private Long id;
    private String title;
    private String titleJpn;
    private String author;
    private String description;
    private String coverUrl;
    private Integer pageCount;
    private Long fileSize;
    private String sourceType;
    private String sourceRef;
    private String category;
    private String status;
    private String lqStatus;
    private Integer progressPercent;
    private Long lastReadChapterId;
    private Integer lastReadPage;
    private List<ChapterVO> chapters;
    private List<TagRef> tags;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @Data
    public static class ChapterVO {
        private Long id;
        private Integer chapterNo;
        private String title;
        private Integer pageCount;
    }

    @Data
    public static class TagRef {
        private String name;
        private String type;
    }
}
