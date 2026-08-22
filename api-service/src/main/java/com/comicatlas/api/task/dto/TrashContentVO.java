package com.comicatlas.api.task.dto;

import lombok.Data;

import java.time.LocalDateTime;

/** 回收站中的漫画、章节或媒体条目。 */
@Data
public class TrashContentVO {
    /** 目标类型：COMIC/CHAPTER/MEDIA */
    private String targetType;
    /** 目标实体 ID */
    private Long targetId;
    /** 所属漫画 ID（COMIC 类型等于 targetId） */
    private Long comicId;
    /** 所属章节 ID（COMIC 类型为 null） */
    private Long chapterId;
    /** 展示标题（MEDIA 为「第N页」） */
    private String title;
    /** 副标题（章节/媒体含所属漫画名） */
    private String subtitle;
    /** 封面读取端点（仅 COMIC 非 null） */
    private String coverUrl;
    /** 生命周期状态（TRASHED 等，三张表枚举值一致） */
    private String status;
    /** 媒体类型：IMAGE/VIDEO（仅 MEDIA 非 null） */
    private String mediaType;
    /** 页码（仅 MEDIA 非 null） */
    private Integer pageNumber;
    /** 创建时间 */
    private LocalDateTime createdAt;
    /** 回收时间（7 天保留期起点） */
    private LocalDateTime trashedAt;
}
