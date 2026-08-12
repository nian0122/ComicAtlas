package com.comicatlas.api.comic.entity;

import lombok.Data;
import java.time.LocalDateTime;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;

/**
 * 漫画分类（name 唯一，被 comic.categoryId 引用）。
 * <p>
 * 数据库实体（DO），禁止直接暴露给接口；对外使用 {@code dto/} 包对应 DTO/VO。
 */
@Data
@TableName("category")
public class Category {
    @TableId(type = IdType.AUTO)
    /** 主键（自增） */
    private Long id;
    /** 分类名称（唯一，最长 64 字符） */
    private String name;
    /** 展示排序序号（默认 0） */
    private Integer sortOrder;
    /** 创建时间（INSERT 时自动填充） */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    /** 更新时间（INSERT/UPDATE 时自动填充） */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
