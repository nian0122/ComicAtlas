package com.comicatlas.worker.persistence.record;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("page")
public class MediaRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long chapterId;

    private Integer pageNumber;

    private String mediaType;   // IMAGE / VIDEO

    private String hqRoot;

    private String hqPath;

    private String hqStatus;     // READY / DELETED / MISSING

    private String lqRoot;

    private String lqPath;

    private String lqStatus;     // READY / NOT_GENERATED / FAILED

    private Long lqSize;         // LQ 文件字节数（未生成时为 null）

    private Long hqSize;

    private Integer width;

    private Integer height;

    private Long duration;       // video only

    private String container;

    private String videoCodec;

    private String audioCodec;

    /** 媒体页生命周期状态（READY/TRASHED/DELETED 等，元数据扫盘快照基线用） */
    private String status;

    /** 乐观锁版本号（元数据扫盘快照基线用） */
    private Integer version;
}
