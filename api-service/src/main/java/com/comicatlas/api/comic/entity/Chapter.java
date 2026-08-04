package com.comicatlas.api.comic.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 章节实体。
 * status 列存储 {@link com.comicatlas.common.enums.ChapterLifecycleStatus} 枚举值。
 */
@Data
@TableName("chapter")
public class Chapter {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long comicId;
    private Long catalogId;
    private String title;
    private String chapterNo;
    private Integer pageCount;
    private Integer sortOrder;
    private Integer globalOrder;

    /** 章节生命周期状态，默认 READY */
    private String status;

    /** 进入 TRASHED 的时间（7 天保留期起点） */
    private LocalDateTime trashedAt;

    /** 乐观锁版本号 */
    @Version
    private Integer version;

    private LocalDateTime createdAt;
}
