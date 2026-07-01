package com.comicatlas.api.comic.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("catalog")
public class Catalog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long comicId;
    private Long parentId;
    private String title;
    private Integer sortOrder;
    private String path;
    private Integer level;
    private LocalDateTime createdAt;
}
