package com.comicatlas.persistence.comic.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.comicatlas.persistence.comic.entity.Catalog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface CatalogMapper extends BaseMapper<Catalog> {

    @Select("SELECT id, parent_id, title, sort_order FROM catalog WHERE comic_id = #{comicId} "
            + "ORDER BY sort_order ASC")
    List<Catalog> selectTreeNodesByComicId(@Param("comicId") Long comicId);
}
