package com.comicatlas.reading.library.dto;

import com.comicatlas.contract.common.enums.ComicStatus;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 漫画列表项视图（阅读端）。
 * <p>
 * 仅含阅读端所需字段；status 为生命周期状态（强类型，序列化为枚举名）。
 * activeTask / allowedOperations 已在管理端独立查询（/api/management/operations），不再冗余返回。
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
    /** 生命周期状态（强类型，对齐前端 comic.status 契约） */
    private ComicStatus status;
    private Integer progressPercent;
    private Long lastReadChapterId;
    private Integer lastReadPage;
    private LocalDateTime createdAt;
}
