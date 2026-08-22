package com.comicatlas.api.upload.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 上传会话中的单个文件（upload_file 表）。
 * <p>
 * fileId 为客户端 opaque 标识；storageName 为服务端生成的 UUID+扩展名，
 * 绝不使用客户端路径拼文件。receivedRanges 记录已接收区间（如 0-65535;131072-196607）。
 * <p>
 * 数据库实体（DO），禁止直接暴露给接口；对外使用 {@code dto/} 包对应 DTO/VO。
 */
@Data
@TableName("upload_file")
public class UploadFile {
    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 关联 upload_session.id */
    private Long sessionId;
    /** 客户端 opaque 文件标识 */
    private String fileId;
    /** 客户端文件名（仅展示，不用于拼路径） */
    private String originalName;
    /** 客户端声明 Content-Type */
    private String contentType;
    /** 声明文件大小（字节） */
    private Long sizeBytes;
    /** 声明文件总 SHA-256 */
    private String sha256;
    /** 服务端生成文件名 uuid.ext */
    private String storageName;
    /** 已接收最大末端字节 */
    private Long receivedBytes;
    /** 已接收区间串，如 0-65535;131072-196607 */
    private String receivedRanges;
    /** complete 时预建的 STAGING media 行 ID */
    private Long mediaId;
    /** 创建时间 */
    private LocalDateTime createdAt;
    /** 更新时间 */
    private LocalDateTime updatedAt;
}
