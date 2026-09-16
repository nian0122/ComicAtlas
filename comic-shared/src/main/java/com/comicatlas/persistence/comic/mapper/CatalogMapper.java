package com.comicatlas.persistence.comic.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.comicatlas.persistence.comic.entity.Catalog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface CatalogMapper extends BaseMapper<Catalog> {

    @Select("SELECT id, comic_id, parent_id, title, sort_order FROM catalog WHERE comic_id = #{comicId} ORDER BY sort_order ASC, id ASC")
    List<Catalog> selectByComicIdOrderBySortOrder(@Param("comicId") Long comicId);

    @Select("SELECT id, comic_id, parent_id, title, sort_order FROM catalog WHERE comic_id = #{comicId} AND parent_id = #{parentId} ORDER BY sort_order ASC, id ASC")
    List<Catalog> selectChildrenByComicIdAndParentId(@Param("comicId") Long comicId, @Param("parentId") Long parentId);

    @Select("SELECT id, comic_id, parent_id, title, sort_order FROM catalog WHERE comic_id = #{comicId} AND parent_id IS NULL ORDER BY sort_order ASC, id ASC")
    List<Catalog> selectRootByComicId(@Param("comicId") Long comicId);

    @Select("SELECT COUNT(*) FROM catalog WHERE comic_id = #{comicId}")
    long countByComicId(@Param("comicId") Long comicId);

    @org.apache.ibatis.annotations.Delete("DELETE FROM catalog WHERE comic_id = #{comicId}")
    int deleteByComicId(@Param("comicId") Long comicId);

    @Select("SELECT id, parent_id, title, sort_order FROM catalog WHERE comic_id = #{comicId} "
            + "ORDER BY sort_order ASC")
    List<Catalog> selectTreeNodesByComicId(@Param("comicId") Long comicId);

}
