package com.comicatlas.api.comic.entity;

import lombok.Data;
import java.time.LocalDateTime;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.Version;
import com.comicatlas.api.common.enums.ChapterLifecycleStatus;

/**
 * 章节实体。
 * status 列存储 {@link com.comicatlas.api.common.enums.ChapterLifecycleStatus} 枚举值。
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

    /** 章节生命周期状态 */
    private ChapterLifecycleStatus status;

    /** 进入 TRASHED 的时间（7 天保留期起点） */
    private LocalDateTime trashedAt;

    /** 乐观锁版本号 */
    @Version
    private Integer version;

    private LocalDateTime createdAt;
}
