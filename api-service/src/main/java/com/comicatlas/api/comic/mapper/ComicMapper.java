package com.comicatlas.api.comic.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.comicatlas.api.comic.entity.Comic;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ComicMapper extends BaseMapper<Comic> {

    @Select("""
        <script>
        SELECT DISTINCT c.* FROM comic c
        LEFT JOIN reading_history rh ON c.id = rh.comic_id
        <where>
            <if test='query.keyword != null and query.keyword != ""'>
                AND (c.title LIKE CONCAT('%', #{query.keyword}, '%')
                     OR c.title_jpn LIKE CONCAT('%', #{query.keyword}, '%')
                     OR c.author LIKE CONCAT('%', #{query.keyword}, '%')
                     OR EXISTS (SELECT 1 FROM comic_tag ct JOIN tag t ON t.id = ct.tag_id
                                 WHERE ct.comic_id = c.id AND t.name LIKE CONCAT('%', #{query.keyword}, '%')))
            </if>
            <if test='query.tag != null and query.tag != ""'>
                AND EXISTS (SELECT 1 FROM comic_tag ct JOIN tag t ON t.id = ct.tag_id
                            WHERE ct.comic_id = c.id AND t.name = #{query.tag})
            </if>
            <if test='query.status != null and query.status != ""'>
                AND c.status = #{query.status}
            </if>
            <if test='query.category != null and query.category != ""'>
                AND c.category = #{query.category}
            </if>
            <if test='query.sourceType != null and query.sourceType != ""'>
                AND c.source_type = #{query.sourceType}
            </if>
        </where>
        ORDER BY
        <choose>
            <when test='query.sort == "lastReadTime"'>rh.updated_at DESC</when>
            <when test='query.sort == "title"'>c.title ASC</when>
            <when test='query.sort == "pageCount"'>c.page_count DESC</when>
            <when test='query.sort == "updatedAt"'>c.updated_at DESC</when>
            <otherwise>c.created_at DESC</otherwise>
        </choose>
        </script>
    """)
    IPage<Comic> selectPage(Page<Comic> page, @Param("query") Object query);
}
