package com.comicatlas.persistence.comic.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.comicatlas.persistence.comic.entity.Comic;
import com.comicatlas.contract.comic.dto.ComicListQuery;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.time.LocalDateTime;
import com.comicatlas.contract.common.enums.MediaReaction;

@Mapper
public interface ComicMapper extends BaseMapper<Comic> {

    @Select("SELECT status FROM comic WHERE id = #{comicId}")
    Comic selectStatusById(@Param("comicId") Long comicId);

    @Select("SELECT title, author, description, category_id FROM comic WHERE id = #{comicId}")
    Comic selectMetadataById(@Param("comicId") Long comicId);

    @Select("SELECT id FROM comic WHERE id = #{comicId}")
    Comic selectReferenceById(@Param("comicId") Long comicId);

    @Select("SELECT id, title, source_type, source_gallery_id, status FROM comic "
            + "WHERE source_type = #{sourceType} AND source_gallery_id = #{galleryId} LIMIT 1")
    Comic selectBySourceTypeAndGalleryId(@Param("sourceType") String sourceType,
                                         @Param("galleryId") String galleryId);

    @Select("SELECT id, title, total_pages FROM comic WHERE id = #{comicId}")
    Comic selectHistoryComicById(@Param("comicId") Long comicId);

    @Select("<script>SELECT id, title, total_pages FROM comic WHERE id IN "
            + "<foreach collection='comicIds' item='comicId' open='(' separator=',' close=')'>#{comicId}</foreach></script>")
    List<Comic> selectHistoryComicsByIds(@Param("comicIds") List<Long> comicIds);

    /** 仅在 READY 时锁定漫画，保证刷新任务并发互斥。 */
    @Update("UPDATE comic SET status = 'REFRESHING' WHERE id = #{comicId} AND status = 'READY'")
    int lockForMetadataRefresh(@Param("comicId") Long comicId);

    /** 取消刷新时仅释放仍处于 REFRESHING 的漫画。 */
    @Update("UPDATE comic SET status = 'READY' WHERE id = #{comicId} AND status = 'REFRESHING'")
    int releaseMetadataRefresh(@Param("comicId") Long comicId);

    /** 仅释放仍处于刷新状态的漫画，避免覆盖并发产生的新状态。 */
    @Update("UPDATE comic SET status = 'READY' WHERE id = #{comicId} AND status = 'REFRESHING'")
    int markRefreshCompleted(@Param("comicId") Long comicId);

    @Update("UPDATE comic SET hq_size = #{hqSize}, lq_size = #{lqSize} WHERE id = #{comicId}")
    int updateStorageStats(@Param("comicId") Long comicId, @Param("hqSize") long hqSize,
                           @Param("lqSize") long lqSize);

    @Update("UPDATE comic SET total_pages = #{totalPages} WHERE id = #{comicId}")
    int updateTotalPages(@Param("comicId") Long comicId, @Param("totalPages") int totalPages);

    @Update("UPDATE comic SET total_pages = #{totalPages}, hq_size = #{hqSize}, lq_size = #{lqSize} WHERE id = #{comicId}")
    int updateAllStats(@Param("comicId") Long comicId, @Param("totalPages") int totalPages,
                       @Param("hqSize") long hqSize, @Param("lqSize") long lqSize);

    @Select("""
        <script>
        SELECT c.id, c.title, c.author, c.total_pages, c.category_id, c.status, c.created_at, c.hq_size, c.reaction, c.reaction_at FROM comic c
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
            <if test='query.hqStatus == "HAS_HQ"'>
                AND EXISTS (
                    SELECT 1 FROM chapter hq_chapter
                    JOIN page hq_page ON hq_page.chapter_id = hq_chapter.id
                    WHERE hq_chapter.comic_id = c.id
                    <if test='activeMediaOnly'> AND hq_chapter.status = 'READY' AND hq_page.status = 'READY' </if>
                      AND hq_page.hq_status = 'READY'
                )
            </if>
            <if test='query.hqStatus == "NO_HQ"'>
                AND NOT EXISTS (
                    SELECT 1 FROM chapter no_hq_chapter
                    JOIN page no_hq_page ON no_hq_page.chapter_id = no_hq_chapter.id
                    WHERE no_hq_chapter.comic_id = c.id
                    <if test='activeMediaOnly'> AND no_hq_chapter.status = 'READY' AND no_hq_page.status = 'READY' </if>
                      AND no_hq_page.hq_status = 'READY'
                )
            </if>
            <if test='query.hqStatus != null and query.hqStatus != "" and query.hqStatus != "HAS_HQ" and query.hqStatus != "NO_HQ"'>
                AND EXISTS (
                    SELECT 1 FROM chapter status_hq_chapter
                    JOIN page status_hq_page ON status_hq_page.chapter_id = status_hq_chapter.id
                    WHERE status_hq_chapter.comic_id = c.id
                    <if test='activeMediaOnly'> AND status_hq_chapter.status = 'READY' AND status_hq_page.status = 'READY' </if>
                      AND status_hq_page.hq_status = #{query.hqStatus}
                )
            </if>
            <if test='query.lqStatus == "HAS_LQ"'>
                AND EXISTS (
                    SELECT 1 FROM chapter has_lq_chapter
                    JOIN page has_lq_page ON has_lq_page.chapter_id = has_lq_chapter.id
                    WHERE has_lq_chapter.comic_id = c.id
                    <if test='activeMediaOnly'> AND has_lq_chapter.status = 'READY' AND has_lq_page.status = 'READY' </if>
                      AND has_lq_page.media_type = 'IMAGE'
                      AND has_lq_page.lq_status = 'READY'
                )
            </if>
            <if test='query.lqStatus != null and query.lqStatus != "" and query.lqStatus != "HAS_LQ" and query.lqStatus != "NO_LQ"'>
                AND EXISTS (
                    SELECT 1 FROM chapter status_lq_chapter
                    JOIN page status_lq_page ON status_lq_page.chapter_id = status_lq_chapter.id
                    WHERE status_lq_chapter.comic_id = c.id
                    <if test='activeMediaOnly'> AND status_lq_chapter.status = 'READY' AND status_lq_page.status = 'READY' </if>
                      AND status_lq_page.media_type = 'IMAGE'
                      AND status_lq_page.lq_status = #{query.lqStatus}
                )
            </if>
            <if test='query.lqStatus == "NO_LQ"'>
                AND NOT EXISTS (
                    SELECT 1 FROM chapter no_lq_chapter
                    JOIN page no_lq_page ON no_lq_page.chapter_id = no_lq_chapter.id
                    WHERE no_lq_chapter.comic_id = c.id
                    <if test='activeMediaOnly'> AND no_lq_chapter.status = 'READY' AND no_lq_page.status = 'READY' </if>
                      AND no_lq_page.media_type = 'IMAGE'
                      AND no_lq_page.lq_status = 'READY'
                )
            </if>
        </where>
        ORDER BY
        <choose>
            <when test='query.sort == "lastReadTime"'>(SELECT MAX(rh.updated_at) FROM reading_history rh WHERE rh.comic_id = c.id)</when>
            <when test='query.sort == "title"'>c.title_sort_key</when>
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
    IPage<Comic> selectPage(Page<Comic> page, @Param("query") ComicListQuery query,
                            @Param("activeMediaOnly") boolean activeMediaOnly);

    @Update("UPDATE comic SET reaction = #{reaction}, reaction_at = #{reactionAt} WHERE id = #{comicId} AND status = 'READY'")
    int updateReaction(@Param("comicId") Long comicId, @Param("reaction") MediaReaction reaction,
                       @Param("reactionAt") LocalDateTime reactionAt);

    @Update({"<script>",
            "UPDATE comic SET reaction = #{reaction}, reaction_at = #{reactionAt} ",
            "WHERE status != 'DELETED' AND id IN ",
            "<foreach collection='comicIds' item='comicId' open='(' separator=',' close=')'>#{comicId}</foreach>",
            "</script>"})
    int updateReactionBatch(@Param("comicIds") List<Long> comicIds,
                            @Param("reaction") MediaReaction reaction,
                            @Param("reactionAt") LocalDateTime reactionAt);

    /** 数据库按 ICU 排序键去重和截取，禁止在应用层全量读取后排序。 */
    @Select("""
        SELECT MIN(title) AS title FROM comic
        WHERE title LIKE #{pattern} OR title_jpn LIKE #{pattern}
        GROUP BY BINARY title, title_sort_key
        ORDER BY title_sort_key ASC, MIN(id) ASC
        LIMIT #{limit}
        """)
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
