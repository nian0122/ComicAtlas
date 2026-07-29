package com.comicatlas.api.importer.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("recovery_task")
public class RecoveryTask {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String status;
    private Integer totalComics;
    private Integer recoveredComics;
    private Integer skippedComics;
    private Integer placeholderComics;
    private Integer errorComics;
    private String errorMessage;
    private String errorDetails;
    private Integer retryCount;
    private LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
}
