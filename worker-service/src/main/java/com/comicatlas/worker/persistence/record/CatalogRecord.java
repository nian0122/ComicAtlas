package com.comicatlas.worker.persistence.record;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("catalog")
public class CatalogRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long comicId;

    private Long parentId;

    private String title;

    private Integer sortOrder;
}
