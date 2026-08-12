package com.comicatlas.persistence.comic.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.comicatlas.persistence.comic.entity.ComicTag;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ComicTagMapper extends BaseMapper<ComicTag> {
}
