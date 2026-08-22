package com.comicatlas.persistence.comic.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.comicatlas.persistence.comic.entity.Comic;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ComicMapper extends BaseMapper<Comic> {

    @Select("""
        <script>
        SELECT c.id, c.title, c.author, c.total_pages, c.category_id, c.status, c.created_at, c.hq_size FROM comic c
        <where>
            <choose>
                <when test='query.status != null and query.status != ""'>
                    AND c.status = #{query.status}
                </when>
                <otherwise>
                    AND c.status = 'READY'
                </otherwise>
            </choose>
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
            <if test='query.tags != null and query.tags.size > 0'>
                <choose>
                    <when test='query.tags.contains(&quot;_NONE&quot;)'>
                        AND NOT EXISTS (SELECT 1 FROM comic_tag ct WHERE ct.comic_id = c.id)
                    </when>
                    <otherwise>
                        <choose>
                            <when test='query.tagMode == &quot;NOT&quot;'>
                                AND NOT EXISTS (SELECT 1 FROM comic_tag ct JOIN tag t ON t.id = ct.tag_id
                                                WHERE ct.comic_id = c.id AND t.name IN
                                                <foreach collection='query.tags' item='tagName' open='(' separator=',' close=')'>#{tagName}</foreach>)
                            </when>
                            <when test='query.tagMode == &quot;AND&quot;'>
                                AND (SELECT COUNT(DISTINCT t.name) FROM comic_tag ct JOIN tag t ON t.id = ct.tag_id
                                     WHERE ct.comic_id = c.id AND t.name IN
                                     <foreach collection='query.tags' item='tagName' open='(' separator=',' close=')'>#{tagName}</foreach>
                                    ) = #{query.tagCount}
                            </when>
                            <otherwise>
                                AND EXISTS (SELECT 1 FROM comic_tag ct JOIN tag t ON t.id = ct.tag_id
                                            WHERE ct.comic_id = c.id AND t.name IN
                                            <foreach collection='query.tags' item='tagName' open='(' separator=',' close=')'>#{tagName}</foreach>
                                           )
                            </otherwise>
                        </choose>
                    </otherwise>
                </choose>
            </if>
            <if test='query.status != null and query.status != ""'>
                AND c.status = #{query.status}
            </if>
            <if test='query.category != null and query.category != ""'>
                <choose>
                    <when test='query.category == &quot;_NONE&quot;'>
                        AND c.category_id IS NULL
                    </when>
                    <otherwise>
                        AND EXISTS (SELECT 1 FROM category cat WHERE cat.id = c.category_id AND cat.name = #{query.category})
                    </otherwise>
                </choose>
            </if>
            <if test='query.sourceType != null and query.sourceType != ""'>
                AND c.source_type = #{query.sourceType}
            </if>
        </where>
        ORDER BY
        <choose>
            <when test='query.sort == "lastReadTime"'>(SELECT MAX(rh.updated_at) FROM reading_history rh WHERE rh.comic_id = c.id)</when>
            <when test='query.sort == "title"'>c.title</when>
            <when test='query.sort == "pageCount"'>c.total_pages</when>
            <when test='query.sort == "fileSize"'>c.hq_size</when>
            <when test='query.sort == "updatedAt"'>c.updated_at</when>
            <otherwise>c.created_at</otherwise>
        </choose>
        <choose>
            <when test='query.order == "asc"'> ASC</when>
            <otherwise> DESC</otherwise>
        </choose>
        , c.id ASC
        </script>
    """)
    IPage<Comic> selectPage(Page<Comic> page, @Param("query") Object query);

    @Select("SELECT title FROM comic WHERE title LIKE #{pattern} OR title_jpn LIKE #{pattern} LIMIT #{limit}")
    List<String> selectTitlesLike(@Param("pattern") String pattern, @Param("limit") int limit);

    /**
     * 行锁读取：串行化同一漫画的并发最终化（completed/failed）处理，防止 lost update。
     * 必须在事务内调用，事务提交/回滚后释放锁。
     */
    @Select("""
        SELECT id, title, title_jpn, author, description, total_pages, hq_size, lq_size,
               source_type, source_gallery_id, source_gallery_token, source_ref,
               storage_policy, status, category_id, category, deleted_at, trashed_at,
               version, created_at, updated_at
        FROM comic WHERE id = #{id} FOR UPDATE
        """)
    Comic selectByIdForUpdate(@Param("id") Long id);

    /**
     * 批量操作 FILTER 解析：返回匹配筛选条件的全部漫画 id。
     * <p>
     * 与列表查询不同：不强制 READY（批量可作用于 TRASHED/DRAFT 等），
     * 按 {@code id ASC} 稳定排序，最多返回 limit 行（用于探测超限）。
     */
    @Select("""
        <script>
        SELECT c.id FROM comic c
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
            <if test='query.tags != null and query.tags.size > 0'>
                <choose>
                    <when test='query.tags.contains(&quot;_NONE&quot;)'>
                        AND NOT EXISTS (SELECT 1 FROM comic_tag ct WHERE ct.comic_id = c.id)
                    </when>
                    <otherwise>
                        <choose>
                            <when test='query.tagMode == &quot;NOT&quot;'>
                                AND NOT EXISTS (SELECT 1 FROM comic_tag ct JOIN tag t ON t.id = ct.tag_id
                                                WHERE ct.comic_id = c.id AND t.name IN
                                                <foreach collection='query.tags' item='tagName' open='(' separator=',' close=')'>#{tagName}</foreach>)
                            </when>
                            <when test='query.tagMode == &quot;AND&quot;'>
                                AND (SELECT COUNT(DISTINCT t.name) FROM comic_tag ct JOIN tag t ON t.id = ct.tag_id
                                     WHERE ct.comic_id = c.id AND t.name IN
                                     <foreach collection='query.tags' item='tagName' open='(' separator=',' close=')'>#{tagName}</foreach>
                                    ) = #{query.tagCount}
                            </when>
                            <otherwise>
                                AND EXISTS (SELECT 1 FROM comic_tag ct JOIN tag t ON t.id = ct.tag_id
                                            WHERE ct.comic_id = c.id AND t.name IN
                                            <foreach collection='query.tags' item='tagName' open='(' separator=',' close=')'>#{tagName}</foreach>
                                           )
                            </otherwise>
                        </choose>
                    </otherwise>
                </choose>
            </if>
            <if test='query.status != null and query.status != ""'>
                AND c.status = #{query.status}
            </if>
            <if test='query.category != null and query.category != ""'>
                <choose>
                    <when test='query.category == &quot;_NONE&quot;'>
                        AND c.category_id IS NULL
                    </when>
                    <otherwise>
                        AND EXISTS (SELECT 1 FROM category cat WHERE cat.id = c.category_id AND cat.name = #{query.category})
                    </otherwise>
                </choose>
            </if>
            <if test='query.sourceType != null and query.sourceType != ""'>
                AND c.source_type = #{query.sourceType}
            </if>
        </where>
        ORDER BY c.id ASC
        LIMIT #{limit}
        </script>
    """)
    List<Long> selectIdsByQuery(@Param("query") com.comicatlas.contract.comic.dto.ComicListQuery query,
                                @Param("limit") int limit);
}
