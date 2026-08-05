package com.comicatlas.api.comic.entity;

import com.comicatlas.api.common.enums.ComicStatus;
import com.comicatlas.api.common.enums.SourceType;
import lombok.Data;
import java.time.LocalDateTime;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.Version;

/**
 * 漫画实体。
 * status 列存储 {@link ComicStatus} 枚举值（DB VARCHAR，存枚举 name()）。
 * sourceType 列存储 {@link SourceType} 枚举值（DB VARCHAR，存枚举 name()）。
 * version 列用于乐观锁（MyBatis Plus @Version 自动管理）。
 */
@Data
@TableName("comic")
public class Comic {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String title;
    private String titleJpn;
    private String author;
    private String description;
    private Integer totalPages;
    private Long fileSize;
    private Long hqSize;
    private Long lqSize;
    private SourceType sourceType;
    private String sourceGalleryId;
    private String sourceGalleryToken;
    private String sourceRef;
    private String storagePolicy;
    private ComicStatus status;
    private Long categoryId;
    private String category;
    private LocalDateTime deletedAt;
    /** 进入 TRASHED 的时间（7 天保留期起点） */
    private LocalDateTime trashedAt;

    /** 乐观锁版本号 */
    @Version
    private Integer version;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
