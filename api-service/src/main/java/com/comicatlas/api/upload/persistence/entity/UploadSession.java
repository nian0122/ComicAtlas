package com.comicatlas.api.upload.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.comicatlas.api.upload.domain.UploadSessionStatus;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 上传会话（upload_session 表）。
 * <p>
 * sessionId 为对外 opaque 标识（UUID），DB 主键 id 作为管理任务 targetId。
 * <p>
 * 数据库实体（DO），禁止直接暴露给接口；对外使用 {@code dto/} 包对应 DTO/VO。
 */
@Data
@TableName("upload_session")
public class UploadSession {
    /** 主键（作为管理任务 targetId） */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 对外 opaque 会话 ID（UUID） */
    private String sessionId;
    /** 目标漫画 ID */
    private Long comicId;
    /** 目标章节 ID */
    private Long chapterId;
    /** 替换目标媒体 ID（replace 流程），可空 */
    private Long replaceMediaId;
    /** 会话状态：ACTIVE/COMPLETED/CANCELLED/EXPIRED/FAILED */
    private UploadSessionStatus status;
    /** 会话总字节数 */
    private Long totalBytes;
    /** 会话内文件数 */
    private Integer totalFiles;
    /** 未完成过期时间 */
    private LocalDateTime expiresAt;
    /** complete 时间 */
    private LocalDateTime completedAt;
    /** 创建时间 */
    private LocalDateTime createdAt;
}
