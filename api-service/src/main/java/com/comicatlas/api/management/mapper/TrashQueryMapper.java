package com.comicatlas.api.management.mapper;

import com.comicatlas.api.management.dto.TrashContentVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 回收站跨实体查询。回收状态存于 comic/chapter/page 三张表，不能复用漫画列表接口。 */
@Mapper
public interface TrashQueryMapper {

    String UNION_SQL = """
        SELECT 'COMIC' AS target_type, c.id AS target_id, c.id AS comic_id, NULL AS chapter_id,
               c.title AS title, c.author AS subtitle, CONCAT('/api/manage/trash/comics/', c.id, '/cover') AS cover_url,
               c.status AS status, NULL AS media_type,
               NULL AS page_number, c.created_at AS created_at, c.trashed_at AS trashed_at
          FROM comic c
        UNION ALL
        SELECT 'CHAPTER', ch.id, ch.comic_id, ch.id, ch.title, c.title, NULL, ch.status, NULL,
               NULL, ch.created_at, ch.trashed_at
          FROM chapter ch JOIN comic c ON c.id = ch.comic_id
        UNION ALL
        SELECT 'MEDIA', m.id, c.id, m.chapter_id,
               CONCAT('第', COALESCE(m.original_page_number, ABS(m.page_number), m.id), '页'),
               CONCAT(c.title, ' / ', COALESCE(ch.title, '未命名章节')),
               NULL, m.status, m.media_type, m.page_number, m.created_at, m.trashed_at
          FROM page m JOIN chapter ch ON ch.id = m.chapter_id JOIN comic c ON c.id = ch.comic_id
        """;

    @Select("""
        <script>
        SELECT * FROM (""" + UNION_SQL + """
        ) trash
        WHERE trash.status = #{status}
          <if test='keyword != null and keyword != ""'>
            AND (trash.title LIKE CONCAT('%', #{keyword}, '%')
                 OR trash.subtitle LIKE CONCAT('%', #{keyword}, '%'))
          </if>
        ORDER BY COALESCE(trash.trashed_at, trash.created_at) DESC, trash.target_type, trash.target_id DESC
        LIMIT #{offset}, #{size}
        </script>
        """)
    List<TrashContentVO> selectPage(@Param("status") String status,
                                    @Param("keyword") String keyword,
                                    @Param("offset") int offset,
                                    @Param("size") int size);

    @Select("""
        <script>
        SELECT COUNT(*) FROM (""" + UNION_SQL + """
        ) trash
        WHERE trash.status = #{status}
          <if test='keyword != null and keyword != ""'>
            AND (trash.title LIKE CONCAT('%', #{keyword}, '%')
                 OR trash.subtitle LIKE CONCAT('%', #{keyword}, '%'))
          </if>
        </script>
        """)
    long count(@Param("status") String status, @Param("keyword") String keyword);
}
