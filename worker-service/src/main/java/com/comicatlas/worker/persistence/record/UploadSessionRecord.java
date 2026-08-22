package com.comicatlas.worker.persistence.record;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 上传会话（只读视图，SELECT-only）。
 */
@Data
@TableName("upload_session")
public class UploadSessionRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String sessionId;

    private Long comicId;

    private Long chapterId;

    private Long replaceMediaId;

    private String status;
}
