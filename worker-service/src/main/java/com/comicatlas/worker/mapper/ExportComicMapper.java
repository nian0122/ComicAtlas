package com.comicatlas.worker.mapper;

import com.comicatlas.worker.entity.ExportComic;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ExportComicMapper {

    /**
     * 查询漫画及当前分类名：优先取 category_id 关联名称，遗留 comic.category 仅作兼容回退。
     */
    @Select("""
        SELECT c.id, c.title, c.author,
               COALESCE(cat.name, c.category) AS category,
               c.status, c.cover_path
        FROM comic c
        LEFT JOIN category cat ON cat.id = c.category_id
        WHERE c.id = #{id}
        """)
    ExportComic selectById(Long id);

    /**
     * 按 comicId 查询有序标签名列表（按 tag_id 升序保证稳定）。
     */
    @Select("""
        SELECT t.name
        FROM comic_tag ct
        JOIN tag t ON t.id = ct.tag_id
        WHERE ct.comic_id = #{comicId}
        ORDER BY ct.tag_id ASC
        """)
    List<String> selectTagNamesByComicId(@Param("comicId") Long comicId);
}
