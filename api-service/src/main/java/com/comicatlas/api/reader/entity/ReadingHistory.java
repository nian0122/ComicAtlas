package com.comicatlas.api.reader.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("reading_history")
public class ReadingHistory {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long comicId;
    private Long chapterId;
    private Integer pageNumber;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
