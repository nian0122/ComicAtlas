package com.comicatlas.api.comic.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("page")
public class Page {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long chapterId;
    private Integer pageNumber;
    private String imageName;
    private String lqStatus;
    private Integer width;
    private Integer height;
    private Long fileSize;
    private LocalDateTime createdAt;
}
