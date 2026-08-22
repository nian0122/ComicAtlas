package com.comicatlas.worker.exporter.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 上传会话内文件（只读视图，SELECT-only）。
 */
@Data
@TableName("upload_file")
public class ExportUploadFile {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long sessionId;

    private String fileId;

    private String storageName;

    private Long sizeBytes;

    private String sha256;

    private Long mediaId;
}
