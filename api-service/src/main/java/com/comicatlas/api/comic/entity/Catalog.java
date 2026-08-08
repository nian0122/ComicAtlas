package com.comicatlas.api.comic.entity;

import lombok.Data;
import java.time.LocalDateTime;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;

/**
 * 漫画目录树节点（多级树结构，parentId 关联父节点，null 为顶层）。
 * <p>
 * 数据库实体（DO），禁止直接暴露给接口；对外使用 {@code dto/} 包对应 DTO/VO。
 */
@Data
@TableName("catalog")
public class Catalog {
    @TableId(type = IdType.AUTO)
    /** 主键（自增） */
    private Long id;
    /** 所属漫画 ID（级联删除） */
    private Long comicId;
    /** 父目录节点 ID，null 表示顶层（级联删除） */
    private Long parentId;
    /** 目录标题（同漫画同父下唯一） */
    private String title;
    /** 同级排序序号（默认 0） */
    private Integer sortOrder;
    /** 创建时间（数据库默认 CURRENT_TIMESTAMP） */
    private LocalDateTime createdAt;
}
