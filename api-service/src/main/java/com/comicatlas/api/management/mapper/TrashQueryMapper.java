package com.comicatlas.api.management.mapper;

import com.comicatlas.api.management.dto.TrashContentVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 回收站跨实体查询。回收状态存于 comic/chapter/page 三张表，不能复用漫画列表接口。 */
@Mapper
public interface TrashQueryMapper {

    /**
     * 回收站跨表 UNION 查询。
     * <p>
     * 历史迁移中 {@code comic} 表显式 {@code COLLATE=utf8mb4_unicode_ci}，而 {@code chapter}/{@code page}
     * 表仅指定 {@code DEFAULT CHARSET=utf8mb4} 未固定排序规则，随服务器/连接默认漂移到
     * {@code utf8mb4_0900_ai_ci}，导致 UNION 合并时抛 {@code Illegal mix of collations}（MySQL 1271）。
     * 因此对全部字符输出列显式 {@code COLLATE=utf8mb4_unicode_ci}，与 comic 表保持一致，
     * 不依赖各表默认排序规则。
     */
    String UNION_SQL = """
        SELECT 'COMIC' AS target_type, c.id AS target_id, c.id AS comic_id, NULL AS chapter_id,
               c.title COLLATE utf8mb4_unicode_ci AS title,
               c.author COLLATE utf8mb4_unicode_ci AS subtitle,
               CONCAT('/api/manage/trash/comics/', c.id, '/cover') COLLATE utf8mb4_unicode_ci AS cover_url,
               c.status COLLATE utf8mb4_unicode_ci AS status, NULL AS media_type,
               NULL AS page_number, c.created_at AS created_at, c.trashed_at AS trashed_at
          FROM comic c
        UNION ALL
        SELECT 'CHAPTER', ch.id, ch.comic_id, ch.id,
               ch.title COLLATE utf8mb4_unicode_ci,
               c.title COLLATE utf8mb4_unicode_ci, NULL,
               ch.status COLLATE utf8mb4_unicode_ci, NULL, NULL,
               ch.created_at, ch.trashed_at
          FROM chapter ch JOIN comic c ON c.id = ch.comic_id
        UNION ALL
        SELECT 'MEDIA', m.id, c.id, m.chapter_id,
               CONCAT('第', COALESCE(m.original_page_number, ABS(m.page_number), m.id), '页') COLLATE utf8mb4_unicode_ci,
               CONCAT(c.title, ' / ', COALESCE(ch.title, '未命名章节')) COLLATE utf8mb4_unicode_ci,
               NULL, m.status COLLATE utf8mb4_unicode_ci,
               m.media_type COLLATE utf8mb4_unicode_ci, m.page_number,
               m.created_at, m.trashed_at
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
