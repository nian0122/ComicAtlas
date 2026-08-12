package com.comicatlas.api.comic.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.comicatlas.api.comic.entity.Catalog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CatalogMapper extends BaseMapper<Catalog> {
}
