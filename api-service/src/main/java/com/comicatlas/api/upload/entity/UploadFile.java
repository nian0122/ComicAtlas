package com.comicatlas.api.upload.entity;

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
 */
@Data
@TableName("upload_file")
public class UploadFile {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long sessionId;
    private String fileId;
    private String originalName;
    private String contentType;
    private Long sizeBytes;
    private String sha256;
    private String storageName;
    private Long receivedBytes;
    private String receivedRanges;
    private Long mediaId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
