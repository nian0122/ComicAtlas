package com.comicatlas.api.export.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("export_task")
public class ExportTask {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long comicId;
    private String status;      // PENDING, RUNNING, SUCCESS, FAILED
    private Integer progress;   // 0-100, -1 on FAILED
    private String outputRoot;
    private String outputPath;
    private Long outputSize;
    private String errorMsg;
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;

    public boolean isPending() { return "PENDING".equals(status); }
    public boolean isRunning() { return "RUNNING".equals(status); }
    public boolean isSuccess() { return "SUCCESS".equals(status); }
    public boolean isFailed() { return "FAILED".equals(status); }
}
