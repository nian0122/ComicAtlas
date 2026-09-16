package com.comicatlas.persistence.comic.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.comicatlas.persistence.comic.entity.ComicTag;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ComicTagMapper extends BaseMapper<ComicTag> {

    @Select("SELECT tag_id FROM comic_tag WHERE comic_id = #{comicId}")
    List<ComicTag> selectByComicId(@Param("comicId") Long comicId);

    @Select("SELECT tag_id FROM comic_tag WHERE comic_id = #{comicId}")
    List<Long> selectTagIdsByComicId(@Param("comicId") Long comicId);
}
