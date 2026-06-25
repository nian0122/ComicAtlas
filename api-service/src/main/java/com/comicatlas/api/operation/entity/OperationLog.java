package com.comicatlas.api.operation.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("operation_log")
public class OperationLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String traceId;
    private String module;
    private String action;
    private String businessId;
    private String detail;
    private LocalDateTime createdAt;
}
