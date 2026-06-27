package com.comicatlas.api.comic.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("comic")
public class Comic {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String title;
    private String titleJpn;
    private String author;
    private String coverPath;
    private Integer totalPages;
    private Long fileSize;
    private Long hqSize;
    private Long lqSize;
    private String sourceType;
    private String sourceGalleryId;
    private String sourceGalleryToken;
    private String sourceUrl;
    private String storageType;
    private String rootKey;
    private String relativePath;
    private String status;
    private String lqStatus;
    private String category;
    private LocalDateTime deletedAt;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
