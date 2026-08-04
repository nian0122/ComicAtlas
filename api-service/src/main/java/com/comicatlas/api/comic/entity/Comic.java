package com.comicatlas.api.comic.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 漫画实体。
 * status 列存储 {@link com.comicatlas.api.common.enums.ComicStatus} 枚举值。
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
    private String sourceType;
    private String sourceGalleryId;
    private String sourceGalleryToken;
    private String sourceRef;
    private String storagePolicy;
    private String status;
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
