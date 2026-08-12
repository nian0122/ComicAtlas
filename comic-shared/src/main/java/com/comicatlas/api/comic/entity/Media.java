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
    /** 主键（自增） */
    private Long id;
    /** 所属章节 ID */
    private Long chapterId;
    /** 章节内页码（回收前唯一） */
    private Integer pageNumber;
    /** 回收前原页码，恢复时优先复用；回收期间 pageNumber 置 -id 释放唯一槽位 */
    private Integer originalPageNumber;
    /** HQ 存储根（固定 HQ） */
    private String hqRoot;
    /** HQ 相对路径（{comicId}/{chapterId}/文件名） */
    private String hqPath;
    /** LQ 存储根（固定 LQ） */
    private String lqRoot;
    /** LQ 相对路径 */
    private String lqPath;
    /** HQ 文件状态（PENDING/READY/DELETED 等） */
    private HqStatus hqStatus;
    /** LQ 生成状态（NOT_GENERATED/READY/FAILED 等） */
    private LqStatus lqStatus;
    /** 视频转码状态（NOT_NEEDED/TRANSCODING/READY 等） */
    private TranscodeStatus transcodeStatus;

    /** 媒体页生命周期状态，默认 READY */
    private MediaLifecycleStatus status;

    /** 进入 TRASHED 的时间（7 天保留期起点） */
    private LocalDateTime trashedAt;

    /** LQ 产物字节数（未生成时为 0） */
    private Long lqSize;
    /** 媒体宽度（像素，图片为原图尺寸，视频为画面尺寸） */
    private Integer width;
    /** 媒体高度（像素） */
    private Integer height;
    /** 源文件字节数 */
    private Long fileSize;
    /** 媒体类型：IMAGE 或 VIDEO（默认 IMAGE，支持图片+视频混排） */
    private String mediaType;
    /** 视频时长（秒），仅 VIDEO 有意义 */
    private BigDecimal duration;
    /** 视频容器格式（如 mp4/webm/mkv） */
    private String container;
    /** 视频编码（如 h264），仅 VIDEO 有意义 */
    private String videoCodec;
    /** 音频编码（如 aac），仅 VIDEO 有意义 */
    private String audioCodec;

    /** 乐观锁版本号 */
    @Version
    private Integer version;

    /** 创建时间 */
    private LocalDateTime createdAt;
}
