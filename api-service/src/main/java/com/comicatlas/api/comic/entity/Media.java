package com.comicatlas.api.comic.entity;

import com.comicatlas.api.common.enums.HqStatus;
import com.comicatlas.api.common.enums.LqStatus;
import com.comicatlas.api.common.enums.MediaLifecycleStatus;
import com.comicatlas.api.common.enums.TranscodeStatus;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.Version;

/**
 * 媒体页实体（映射 page 表）。
 * <ul>
 *   <li>status 列: {@link com.comicatlas.api.common.enums.MediaLifecycleStatus}</li>
 *   <li>hqStatus 列: {@link com.comicatlas.api.common.enums.HqStatus}</li>
 *   <li>lqStatus 列: {@link com.comicatlas.api.common.enums.LqStatus}</li>
 *   <li>transcodeStatus 列: {@link com.comicatlas.api.common.enums.TranscodeStatus}</li>
 * </ul>
 * <p>数据库实体（DO），禁止直接暴露给接口；对外使用 {@code dto/} 包对应 DTO/VO。
 */
@Data
@TableName("page")
public class Media {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long chapterId;
    private Integer pageNumber;
    /** 回收前原页码，恢复时优先复用；回收期间 pageNumber 置 -id 释放唯一槽位 */
    private Integer originalPageNumber;
    private String hqRoot;
    private String hqPath;
    private String lqRoot;
    private String lqPath;
    private HqStatus hqStatus;
    private LqStatus lqStatus;
    private TranscodeStatus transcodeStatus;

    /** 媒体页生命周期状态，默认 READY */
    private MediaLifecycleStatus status;

    /** 进入 TRASHED 的时间（7 天保留期起点） */
    private LocalDateTime trashedAt;

    private Long lqSize;
    private Integer width;
    private Integer height;
    private Long fileSize;
    /** 媒体类型：IMAGE 或 VIDEO（默认 IMAGE，支持图片+视频混排） */
    private String mediaType;
    /** 视频时长（秒），仅 VIDEO 有意义 */
    private BigDecimal duration;
    /** 视频容器格式（如 mp4/webm/mkv） */
    private String container;
    private String videoCodec;
    private String audioCodec;

    /** 乐观锁版本号 */
    @Version
    private Integer version;

    private LocalDateTime createdAt;
}
