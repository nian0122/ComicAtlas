package com.comicatlas.api.upload.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 上传会话（upload_session 表）。
 * <p>
 * sessionId 为对外 opaque 标识（UUID），DB 主键 id 作为管理任务 targetId。
 */
@Data
@TableName("upload_session")
public class UploadSession {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String sessionId;
    private Long comicId;
    private Long chapterId;
    private Long replaceMediaId;
    private String status;
    private Long totalBytes;
    private Integer totalFiles;
    private LocalDateTime expiresAt;
    private LocalDateTime completedAt;
    private LocalDateTime createdAt;
}
