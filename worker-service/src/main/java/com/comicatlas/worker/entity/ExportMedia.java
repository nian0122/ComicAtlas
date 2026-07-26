package com.comicatlas.worker.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("page")
public class ExportMedia {

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

    private Long fileSize;

    private Integer width;

    private Integer height;

    private Long duration;       // video only

    private String container;

    private String videoCodec;

    private String audioCodec;
}
