package com.comicatlas.api.comic.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("comic_tag")
public class ComicTag {
    private Long comicId;
    private Long tagId;
}
