package com.comicatlas.persistence.comic.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 漫画与标签的多对多关联表（复合主键 comicId+tagId）。
 * <p>
 * 数据库实体（DO），禁止直接暴露给接口；对外使用 {@code dto/} 包对应 DTO/VO。
 */
@Data
@TableName("comic_tag")
public class ComicTag {
    /** 漫画 ID（级联删除） */
    private Long comicId;
    /** 标签 ID（级联删除） */
    private Long tagId;
}
