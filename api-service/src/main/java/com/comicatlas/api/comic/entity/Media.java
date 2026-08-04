package com.comicatlas.api.comic.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 媒体页实体（映射 page 表）。
 * <ul>
 *   <li>status 列: {@link com.comicatlas.common.enums.MediaLifecycleStatus}</li>
 *   <li>hqStatus 列: {@link com.comicatlas.api.common.enums.HqStatus}</li>
 *   <li>lqStatus 列: {@link com.comicatlas.api.common.enums.LqStatus}</li>
 *   <li>transcodeStatus 列: {@link com.comicatlas.common.enums.TranscodeStatus}</li>
 * </ul>
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
    private String hqStatus;
    private String lqStatus;
    private String transcodeStatus;

    /** 媒体页生命周期状态，默认 READY */
    private String status;

    /** 进入 TRASHED 的时间（7 天保留期起点） */
    private LocalDateTime trashedAt;

    private Long lqSize;
    private Integer width;
    private Integer height;
    private Long fileSize;
    private String mediaType;
    private BigDecimal duration;
    private String container;
    private String videoCodec;
    private String audioCodec;

    /** 乐观锁版本号 */
    @Version
    private Integer version;

    private LocalDateTime createdAt;
}
