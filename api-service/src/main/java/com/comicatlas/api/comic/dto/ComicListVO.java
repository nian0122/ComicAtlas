package com.comicatlas.api.comic.dto;

import com.comicatlas.api.management.dto.ManagementTaskResponse;
import com.comicatlas.api.management.policy.AllowedOperations;
import com.comicatlas.common.enums.ComicLifecycleStatus;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 漫画列表项视图。
 * <p>
 * lifecycle / allowedOperations / activeTask 均由服务端强类型返回。
 */
@Data
public class ComicListVO {
    private Long id;
    private String title;
    private String author;
    private String coverUrl;
    private Integer pageCount;
    private Long categoryId;
    private String categoryName;
    /** 生命周期状态（强类型，替代旧 status:string） */
    private ComicLifecycleStatus lifecycle;
    /** 当前活跃的管理任务（无则 null） */
    private ManagementTaskResponse activeTask;
    /** 当前状态下允许的操作与阻塞原因 */
    private AllowedOperations allowedOperations;
    private Integer progressPercent;
    private Long lastReadChapterId;
    private Integer lastReadPage;
    private LocalDateTime createdAt;
}
