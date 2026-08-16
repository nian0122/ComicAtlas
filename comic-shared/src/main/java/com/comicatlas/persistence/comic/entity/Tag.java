package com.comicatlas.persistence.comic.entity;

import lombok.Data;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;

/**
 * 漫画标签（name+type 联合唯一，通过 comic_tag 与漫画多对多关联）。
 * <p>
 * 数据库实体（DO），禁止直接暴露给接口；对外使用 {@code dto/} 包对应 DTO/VO。
 */
@Data
@TableName("tag")
public class Tag {
    @TableId(type = IdType.AUTO)
    /** 主键（自增） */
    private Long id;
    /** 标签名称（与 type 组成唯一索引） */
    private String name;
    /** 标签类型（可选） */
    private String type;
}
