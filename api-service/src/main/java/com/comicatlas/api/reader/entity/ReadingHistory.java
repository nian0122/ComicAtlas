package com.comicatlas.api.reader.entity;

import lombok.Data;
import java.time.LocalDateTime;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;

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
