package com.comicatlas.worker.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("comic")
public class ExportComic {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String title;

    private String author;

    private String category;

    private String status;

    private String coverPath;
}
