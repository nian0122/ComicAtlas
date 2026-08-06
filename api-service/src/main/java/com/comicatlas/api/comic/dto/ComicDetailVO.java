package com.comicatlas.api.comic.dto;

import com.comicatlas.api.common.enums.ComicStatus;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 漫画详情视图。
 * <p>
 * status 为生命周期状态（强类型，序列化为枚举名，对齐前端 comic.status 契约）。
 * 活跃管理任务与允许操作已由管理端独立查询，不再冗余返回。
 */
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
    private Long categoryId;
    private String categoryName;
    /** 生命周期状态（强类型，对齐前端 comic.status 契约） */
    private ComicStatus status;
    /** 乐观锁版本号 */
    private Integer version;
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
