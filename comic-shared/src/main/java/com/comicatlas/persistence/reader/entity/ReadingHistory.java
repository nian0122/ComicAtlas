package com.comicatlas.persistence.reader.entity;

import lombok.Data;
import java.time.LocalDateTime;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;

/**
 * 阅读历史（记录单本漫画最近阅读进度）。
 * <p>
 * 数据库实体（DO），禁止直接暴露给接口；对外使用 {@code dto/} 包对应 DTO/VO。
 */
@Data
@TableName("reading_history")
public class ReadingHistory {
    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 漫画 ID（uk_comic 唯一约束，每本漫画一行） */
    private Long comicId;
    /** 最近阅读的章节 ID */
    private Long chapterId;
    /** 最近阅读页码（默认 1） */
    private Integer pageNumber;
    /** 首次创建时间 */
    private LocalDateTime createdAt;
    /** 最近阅读时间（历史列表按此排序） */
    private LocalDateTime updatedAt;
}
