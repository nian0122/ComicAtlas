package com.comicatlas.persistence.comic.entity;

import lombok.Data;
import java.time.LocalDateTime;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.Version;
import com.comicatlas.contract.common.enums.ChapterLifecycleStatus;

/**
 * 章节实体。
 * status 列存储 {@link com.comicatlas.contract.common.enums.ChapterLifecycleStatus} 枚举值。
 * <p>数据库实体（DO），禁止直接暴露给接口；对外使用 {@code dto/} 包对应 DTO/VO。
 */
@Data
@TableName("chapter")
public class Chapter {
    @TableId(type = IdType.AUTO)
    /** 主键（自增） */
    private Long id;
    /** 所属漫画 ID */
    private Long comicId;
    /** 所属目录节点 ID（可空，表示章节直接挂在漫画根下） */
    private Long catalogId;
    /** 章节标题 */
    private String title;
    /** 原始章节编号（仅展示，不参与排序） */
    private String chapterNo;
    /** 章节页数 */
    private Integer pageCount;
    /** 同目录下排序序号 */
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

    /** 创建时间 */
    private LocalDateTime createdAt;
}
