package com.comicatlas.reading.favorites.persistence.mapper;

import com.comicatlas.reading.favorites.persistence.FavoriteRow;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

/** 按标记层级查询可阅读内容，父级回收时同时隐藏子级。 */
public interface FavoriteMapper {
    @Select("""
        <script>
        <choose>
          <when test="targetType == 'COMIC'">
            SELECT c.id, c.id AS comic_id, c.title AS comic_title, NULL AS chapter_id,
                   c.title, NULL AS page_number, NULL AS media_type, NULL AS duration,
                   NULL AS preview_root, NULL AS preview_path, c.reaction_at,
                   hc.id AS last_read_chapter_id, h.page_number AS last_read_page_number
            FROM comic c LEFT JOIN reading_history h ON h.comic_id = c.id
            LEFT JOIN chapter hc ON hc.id = h.chapter_id AND hc.comic_id = c.id AND hc.status = 'READY'
            WHERE c.status = 'READY' AND c.reaction = 'LIKE'
            ORDER BY c.reaction_at <choose><when test="oldest">ASC</when><otherwise>DESC</otherwise></choose>,
                     c.id <choose><when test="oldest">ASC</when><otherwise>DESC</otherwise></choose>
          </when>
          <when test="targetType == 'CHAPTER'">
            SELECT ch.id, c.id AS comic_id, c.title AS comic_title, ch.id AS chapter_id,
                   ch.title, 1 AS page_number, NULL AS media_type, NULL AS duration,
                   NULL AS preview_root, NULL AS preview_path, ch.reaction_at,
                   h.chapter_id AS last_read_chapter_id, h.page_number AS last_read_page_number
            FROM chapter ch JOIN comic c ON c.id = ch.comic_id
            LEFT JOIN reading_history h ON h.comic_id = c.id AND h.chapter_id = ch.id
            WHERE c.status = 'READY' AND ch.status = 'READY' AND ch.reaction = 'LIKE'
            ORDER BY ch.reaction_at <choose><when test="oldest">ASC</when><otherwise>DESC</otherwise></choose>,
                     ch.id <choose><when test="oldest">ASC</when><otherwise>DESC</otherwise></choose>
          </when>
          <otherwise>
            SELECT p.id, c.id AS comic_id, c.title AS comic_title, ch.id AS chapter_id,
                   ch.title, p.page_number, p.media_type, p.duration,
                   CASE WHEN p.media_type = 'IMAGE' AND p.lq_status = 'READY'
                        THEN p.lq_root ELSE p.hq_root END AS preview_root,
                   CASE WHEN p.media_type = 'IMAGE' AND p.lq_status = 'READY'
                        THEN p.lq_path ELSE p.hq_path END AS preview_path,
                   p.reaction_at, NULL AS last_read_chapter_id, NULL AS last_read_page_number
            FROM page p JOIN chapter ch ON ch.id = p.chapter_id JOIN comic c ON c.id = ch.comic_id
            WHERE c.status = 'READY' AND ch.status = 'READY' AND p.status = 'READY'
              AND p.reaction = 'LIKE'
              AND (p.hq_status = 'READY' OR (p.media_type = 'IMAGE' AND p.lq_status = 'READY'))
            ORDER BY p.reaction_at <choose><when test="oldest">ASC</when><otherwise>DESC</otherwise></choose>,
                     p.id <choose><when test="oldest">ASC</when><otherwise>DESC</otherwise></choose>
          </otherwise>
        </choose>
        LIMIT #{limit} OFFSET #{offset}
        </script>
        """)
    List<FavoriteRow> selectFavorites(@Param("targetType") String targetType, @Param("oldest") boolean oldest,
            @Param("limit") int limit, @Param("offset") long offset);
}
