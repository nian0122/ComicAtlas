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
 * <p>数据库实体（DO），禁止直接暴露给接口；对外使用 {@code dto/} 包对应 DTO/VO。
 */
@Data
@TableName("chapter")
public class Chapter {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long comicId;
    private Long catalogId;
    private String title;
    /** 原始章节编号（仅展示，不参与排序） */
    private String chapterNo;
    private Integer pageCount;
    private Integer sortOrder;
    /** 全书阅读顺序（重排依据，comicId 内唯一） */
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
