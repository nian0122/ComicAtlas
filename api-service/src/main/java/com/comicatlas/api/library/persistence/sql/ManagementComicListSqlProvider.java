package com.comicatlas.api.library.persistence.sql;

/** 管理列表与批量快照共用同一份筛选谓词，防止操作范围偏离列表。 */
public final class ManagementComicListSqlProvider {
    private static final String FILTERS = """
        <where>
            <if test='query.status != null and query.status != ""'> AND c.status = #{query.status} </if>
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
            <if test='query.hqStatus == "HAS_HQ" or query.hqStatus == "ALL_HQ" or query.hqStatus == "PARTIAL_HQ"'>
                AND EXISTS (
                    SELECT 1 FROM chapter hq_chapter
                    JOIN page hq_page ON hq_page.chapter_id = hq_chapter.id
                    WHERE hq_chapter.comic_id = c.id
                    AND hq_chapter.status IN ('STAGING', 'READY') AND hq_page.status IN ('STAGING', 'READY')
                      AND hq_page.hq_status = 'READY'
                )
            </if>
            <if test='query.hqStatus == "ALL_HQ"'>
                AND NOT EXISTS (
                    SELECT 1 FROM chapter incomplete_hq_chapter
                    JOIN page incomplete_hq_page ON incomplete_hq_page.chapter_id = incomplete_hq_chapter.id
                    WHERE incomplete_hq_chapter.comic_id = c.id
                      AND incomplete_hq_chapter.status IN ('STAGING', 'READY') AND incomplete_hq_page.status IN ('STAGING', 'READY')
                      AND (incomplete_hq_page.hq_status IS NULL OR incomplete_hq_page.hq_status != 'READY')
                )
            </if>
            <if test='query.hqStatus == "PARTIAL_HQ"'>
                AND EXISTS (
                    SELECT 1 FROM chapter partial_hq_chapter
                    JOIN page partial_hq_page ON partial_hq_page.chapter_id = partial_hq_chapter.id
                    WHERE partial_hq_chapter.comic_id = c.id
                      AND partial_hq_chapter.status IN ('STAGING', 'READY') AND partial_hq_page.status IN ('STAGING', 'READY')
                      AND (partial_hq_page.hq_status IS NULL OR partial_hq_page.hq_status != 'READY')
                )
            </if>
            <if test='query.hqStatus == "NO_HQ"'>
                AND NOT EXISTS (
                    SELECT 1 FROM chapter no_hq_chapter
                    JOIN page no_hq_page ON no_hq_page.chapter_id = no_hq_chapter.id
                    WHERE no_hq_chapter.comic_id = c.id
                    AND no_hq_chapter.status IN ('STAGING', 'READY') AND no_hq_page.status IN ('STAGING', 'READY')
                      AND no_hq_page.hq_status = 'READY'
                )
            </if>
            <if test='query.hqStatus != null and query.hqStatus != "" and query.hqStatus != "HAS_HQ" and query.hqStatus != "NO_HQ" and query.hqStatus != "ALL_HQ" and query.hqStatus != "PARTIAL_HQ"'>
                AND EXISTS (
                    SELECT 1 FROM chapter status_hq_chapter
                    JOIN page status_hq_page ON status_hq_page.chapter_id = status_hq_chapter.id
                    WHERE status_hq_chapter.comic_id = c.id
                    AND status_hq_chapter.status IN ('STAGING', 'READY') AND status_hq_page.status IN ('STAGING', 'READY')
                      AND status_hq_page.hq_status = #{query.hqStatus}
                )
            </if>
            <if test='query.lqStatus == "HAS_LQ" or query.lqStatus == "ALL_LQ" or query.lqStatus == "PARTIAL_LQ"'>
                AND EXISTS (
                    SELECT 1 FROM chapter has_lq_chapter
                    JOIN page has_lq_page ON has_lq_page.chapter_id = has_lq_chapter.id
                    WHERE has_lq_chapter.comic_id = c.id
                    AND has_lq_chapter.status IN ('STAGING', 'READY') AND has_lq_page.status IN ('STAGING', 'READY')
                      AND has_lq_page.media_type = 'IMAGE'
                      AND has_lq_page.lq_status = 'READY'
                )
            </if>
            <if test='query.lqStatus == "ALL_LQ"'>
                AND NOT EXISTS (
                    SELECT 1 FROM chapter incomplete_lq_chapter
                    JOIN page incomplete_lq_page ON incomplete_lq_page.chapter_id = incomplete_lq_chapter.id
                    WHERE incomplete_lq_chapter.comic_id = c.id
                      AND incomplete_lq_chapter.status IN ('STAGING', 'READY') AND incomplete_lq_page.status IN ('STAGING', 'READY')
                      AND incomplete_lq_page.media_type = 'IMAGE'
                      AND (incomplete_lq_page.lq_status IS NULL OR incomplete_lq_page.lq_status != 'READY')
                )
            </if>
            <if test='query.lqStatus == "PARTIAL_LQ"'>
                AND EXISTS (
                    SELECT 1 FROM chapter partial_lq_chapter
                    JOIN page partial_lq_page ON partial_lq_page.chapter_id = partial_lq_chapter.id
                    WHERE partial_lq_chapter.comic_id = c.id
                      AND partial_lq_chapter.status IN ('STAGING', 'READY') AND partial_lq_page.status IN ('STAGING', 'READY')
                      AND partial_lq_page.media_type = 'IMAGE'
                      AND (partial_lq_page.lq_status IS NULL OR partial_lq_page.lq_status != 'READY')
                )
            </if>
            <if test='query.lqStatus != null and query.lqStatus != "" and query.lqStatus != "HAS_LQ" and query.lqStatus != "NO_LQ" and query.lqStatus != "ALL_LQ" and query.lqStatus != "PARTIAL_LQ"'>
                AND EXISTS (
                    SELECT 1 FROM chapter status_lq_chapter
                    JOIN page status_lq_page ON status_lq_page.chapter_id = status_lq_chapter.id
                    WHERE status_lq_chapter.comic_id = c.id
                    AND status_lq_chapter.status IN ('STAGING', 'READY') AND status_lq_page.status IN ('STAGING', 'READY')
                      AND status_lq_page.media_type = 'IMAGE'
                      AND (status_lq_page.lq_status = #{query.lqStatus}
                           OR (#{query.lqStatus} = 'QUEUED'
                               AND status_lq_page.lq_status IN ('QUEUED', 'GENERATING')))
                )
            </if>
            <if test='query.lqStatus == "NO_LQ"'>
                AND NOT EXISTS (
                    SELECT 1 FROM chapter no_lq_chapter
                    JOIN page no_lq_page ON no_lq_page.chapter_id = no_lq_chapter.id
                    WHERE no_lq_chapter.comic_id = c.id
                    AND no_lq_chapter.status IN ('STAGING', 'READY') AND no_lq_page.status IN ('STAGING', 'READY')
                      AND no_lq_page.media_type = 'IMAGE'
                      AND no_lq_page.lq_status = 'READY'
                )
            </if>
        </where>
            """;
    private static final String ORDER = """
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
            """;

    private ManagementComicListSqlProvider() {
    }

    /** 管理列表分页查询；用户输入始终通过 MyBatis 参数绑定。 */
    public static String selectPage() {
        return "<script>SELECT c.id, c.title, c.author, c.total_pages, c.category_id, c.status, "
                + "c.created_at, c.hq_size, c.reaction, c.reaction_at FROM comic c " + FILTERS + ORDER + "</script>";
    }

    /** 批量快照沿用列表筛选，按主键稳定排序并探测数量上限。 */
    public static String selectIdsByQuery() {
        String exclusions = """
                <if test='excludedIds != null and excludedIds.size > 0'>
                    AND c.id NOT IN
                    <foreach collection='excludedIds' item='excludedId' open='(' separator=',' close=')'>
                        #{excludedId}
                    </foreach>
                </if>
                """;
        return "<script>SELECT c.id FROM comic c " + FILTERS.replace("</where>", exclusions + "</where>")
                + " ORDER BY c.id ASC LIMIT #{limit}</script>";
    }
}
