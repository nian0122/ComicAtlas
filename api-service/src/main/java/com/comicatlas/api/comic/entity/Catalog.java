package com.comicatlas.api.comic.entity;

import lombok.Data;
import java.time.LocalDateTime;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;

@Data
@TableName("catalog")
public class Catalog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long comicId;
    private Long parentId;
    private String title;
    private Integer sortOrder;
    private LocalDateTime createdAt;
}
