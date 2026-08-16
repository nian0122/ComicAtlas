package com.comicatlas.persistence.comic.entity;

import com.comicatlas.contract.common.enums.ComicStatus;
import com.comicatlas.contract.common.enums.SourceType;
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
 * <p>数据库实体（DO），禁止直接暴露给接口；对外使用 {@code dto/} 包对应 DTO/VO。
 */
@Data
@TableName("comic")
public class Comic {
    @TableId(type = IdType.AUTO)
    /** 主键（自增） */
    private Long id;
    /** 漫画标题（必填） */
    private String title;
    /** 日文原标题（可选） */
    private String titleJpn;
    /** 作者（可选） */
    private String author;
    /** 简介（长文本，可选） */
    private String description;
    /** 总页数（默认 0） */
    private Integer totalPages;
    /** HQ 文件占用大小（字节） */
    private Long hqSize;
    /** LQ 图片占用大小（字节） */
    private Long lqSize;
    /** 来源类型（ZIP/DIRECTORY/EHENTAI），存枚举 name() */
    private SourceType sourceType;
    /** EHENTAI gallery id（与 sourceType 组成唯一索引） */
    private String sourceGalleryId;
    /** EHENTAI gallery token */
    private String sourceGalleryToken;
    /** 来源引用（原始路径或链接） */
    private String sourceRef;
    /** 存储策略（统一 MANAGED 受控存储） */
    private String storagePolicy;
    /** 漫画状态（生命周期流转），存枚举 name() */
    private ComicStatus status;
    /** 分类 ID（关联 category 表，可空） */
    private Long categoryId;
    /** 分类名（V3 迁移前的过渡遗留列，已由 {@link #categoryId} 取代；保留供 MetadataExporter 导出历史数据） */
    private String category;
    /** 软删除时间（null 表示未删除） */
    private LocalDateTime deletedAt;
    /** 进入 TRASHED 的时间（7 天保留期起点） */
    private LocalDateTime trashedAt;

    /** 乐观锁版本号 */
    @Version
    private Integer version;

    /** 创建时间（INSERT 时自动填充） */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    /** 更新时间（INSERT/UPDATE 时自动填充） */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
