package com.comicatlas.worker.persistence.record;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("chapter")
public class ChapterRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long comicId;

    private Long catalogId;

    private String title;

    private String chapterNo;

    private Integer sortOrder;

    private Integer globalOrder;

    /** 乐观锁版本号（元数据扫盘快照基线用） */
    private Integer version;
}
