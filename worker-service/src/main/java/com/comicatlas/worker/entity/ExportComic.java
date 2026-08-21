package com.comicatlas.worker.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import com.baomidou.mybatisplus.annotation.TableField;
import java.util.List;

@Data
@TableName("comic")
public class ExportComic {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String title;

    private String author;

    private String description;

    private String category;

    private String status;

    private String coverPath;

    /** 导出查询组装的标签，不对应 comic 表列。 */
    @TableField(exist = false)
    private List<String> tags;
}
