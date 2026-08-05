package com.comicatlas.api.importer.entity;

import lombok.Data;
import java.time.LocalDateTime;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;

@Data
@TableName("recovery_task")
public class RecoveryTask {
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 关联 management_task.id 一对一扩展（V12 列） */
    private Long managementTaskId;
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
