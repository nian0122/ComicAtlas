package com.comicatlas.persistence.comic.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.comicatlas.persistence.comic.entity.Category;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface CategoryMapper extends BaseMapper<Category> {

    @Select("SELECT id, name, sort_order FROM category ORDER BY sort_order ASC")
    java.util.List<Category> selectAllOrderedBySortOrder();

    @Select("SELECT COUNT(*) FROM category WHERE name = #{name}")
    long countByName(@org.apache.ibatis.annotations.Param("name") String name);

    @Select("SELECT COUNT(*) FROM category WHERE name = #{name} AND id <> #{categoryId}")
    long countByNameExcludingId(@org.apache.ibatis.annotations.Param("name") String name,
                                @org.apache.ibatis.annotations.Param("categoryId") Long categoryId);

    @Select("SELECT COUNT(*) FROM category")
    long countAll();
}
