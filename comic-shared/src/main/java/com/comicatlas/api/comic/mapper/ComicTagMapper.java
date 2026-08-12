package com.comicatlas.api.comic.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.comicatlas.api.comic.entity.ComicTag;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ComicTagMapper extends BaseMapper<ComicTag> {
}
