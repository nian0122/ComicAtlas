package com.comicatlas.worker.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("chapter")
public class ExportChapter {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long comicId;

    private Long catalogId;

    private String title;

    private String chapterNo;

    private Integer sortOrder;

    private Integer globalOrder;
}
