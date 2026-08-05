package com.comicatlas.api.comic.dto;

import com.comicatlas.api.common.enums.ComicStatus;
import com.comicatlas.api.management.dto.ManagementTaskResponse;
import com.comicatlas.api.management.policy.AllowedOperations;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 漫画详情视图。
 * <p>
 * 生命周期、活跃管理任务、允许操作均由服务端强类型返回，
 * 前端不得自算操作权限。
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
    /** 生命周期状态（强类型，替代旧 status:string） */
    private ComicStatus lifecycle;
    /** 乐观锁版本号 */
    private Integer version;
    /** 当前活跃的管理任务（无则 null） */
    private ManagementTaskResponse activeTask;
    /** 当前状态下允许的操作与阻塞原因 */
    private AllowedOperations allowedOperations;
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
