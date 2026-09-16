package com.comicatlas.persistence.comic.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.comicatlas.persistence.comic.entity.Chapter;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface ChapterMapper extends BaseMapper<Chapter> {

    @Select("SELECT id, comic_id, catalog_id, chapter_no, title, status, page_count, sort_order, global_order FROM chapter WHERE comic_id = #{comicId} ORDER BY global_order ASC")
    List<Chapter> selectByComicIdOrderByGlobalOrder(@Param("comicId") Long comicId);

    @Select("SELECT id, comic_id, catalog_id, chapter_no, title, status, page_count, sort_order, global_order FROM chapter WHERE comic_id = #{comicId} AND catalog_id = #{catalogId} ORDER BY sort_order DESC, id DESC LIMIT 1")
    Chapter selectLastByComicIdAndCatalogId(@Param("comicId") Long comicId, @Param("catalogId") Long catalogId);

    @Select("SELECT id, comic_id, catalog_id, chapter_no, title, status, page_count, sort_order, global_order FROM chapter WHERE comic_id = #{comicId} AND catalog_id IS NULL ORDER BY sort_order DESC, id DESC LIMIT 1")
    Chapter selectLastByComicIdWithoutCatalog(@Param("comicId") Long comicId);

    @Select("SELECT id, comic_id, catalog_id, chapter_no, title, status, page_count, sort_order, global_order FROM chapter WHERE comic_id = #{comicId} AND catalog_id = #{catalogId} ORDER BY sort_order ASC, id ASC")
    List<Chapter> selectByComicIdAndCatalogId(@Param("comicId") Long comicId, @Param("catalogId") Long catalogId);

    @Select("SELECT id, comic_id, catalog_id, chapter_no, title, status, page_count, sort_order, global_order FROM chapter WHERE comic_id = #{comicId} AND catalog_id IS NULL ORDER BY sort_order ASC, id ASC")
    List<Chapter> selectByComicIdWithoutCatalog(@Param("comicId") Long comicId);

    @Select("SELECT id, comic_id, catalog_id, chapter_no, title, status, page_count, sort_order, global_order FROM chapter WHERE comic_id = #{comicId} ORDER BY global_order DESC LIMIT 1")
    Chapter selectLastByComicId(@Param("comicId") Long comicId);

    @org.apache.ibatis.annotations.Delete("DELETE FROM chapter WHERE comic_id = #{comicId}")
    int deleteByComicId(@Param("comicId") Long comicId);

    @Select("SELECT id, comic_id, title, status, global_order FROM chapter WHERE id = #{chapterId}")
    Chapter selectReaderChapter(@Param("chapterId") Long chapterId);

    @Select("SELECT id, comic_id, page_count, status FROM chapter WHERE id = #{chapterId}")
    Chapter selectReadableById(@Param("chapterId") Long chapterId);

    @Select("SELECT id, chapter_no, page_count FROM chapter WHERE id = #{chapterId}")
    Chapter selectHistoryChapterById(@Param("chapterId") Long chapterId);

    @Select("<script>SELECT id, chapter_no, page_count FROM chapter WHERE id IN "
            + "<foreach collection='chapterIds' item='chapterId' open='(' separator=',' close=')'>#{chapterId}</foreach></script>")
    List<Chapter> selectHistoryChaptersByIds(@Param("chapterIds") List<Long> chapterIds);

    @Select("SELECT id, catalog_id, chapter_no, title, global_order, page_count FROM chapter "
            + "WHERE comic_id = #{comicId} AND status = 'READY' ORDER BY global_order ASC")
    List<Chapter> selectReadyCatalogChapters(@Param("comicId") Long comicId);

    @Select("SELECT id FROM chapter WHERE comic_id = #{comicId} AND status = 'READY' "
            + "AND global_order < #{globalOrder} ORDER BY global_order DESC LIMIT 1")
    Long selectPreviousReadyChapterId(@Param("comicId") Long comicId, @Param("globalOrder") Integer globalOrder);

    @Select("SELECT id FROM chapter WHERE comic_id = #{comicId} AND status = 'READY' "
            + "AND global_order > #{globalOrder} ORDER BY global_order ASC LIMIT 1")
    Long selectNextReadyChapterId(@Param("comicId") Long comicId, @Param("globalOrder") Integer globalOrder);

    @Select("SELECT id, comic_id, catalog_id, title, chapter_no, page_count, global_order, status "
            + "FROM chapter WHERE comic_id = #{comicId} AND status = 'READY' ORDER BY chapter_no ASC")
    List<Chapter> selectReadyByComicIdOrderByChapterNo(@Param("comicId") Long comicId);

    @Select("SELECT id, comic_id, catalog_id, chapter_no, title, global_order, page_count, status "
            + "FROM chapter WHERE comic_id = #{comicId}")
    List<Chapter> selectByComicId(@Param("comicId") Long comicId);

    @Select("SELECT COUNT(*) FROM chapter WHERE comic_id = #{comicId}")
    long countByComicId(@Param("comicId") Long comicId);

    /**
     * 将漫画章节临时置为互不冲突的负序号，供全局重排的第二阶段使用。
     */
    @Update("UPDATE chapter SET global_order = -id WHERE comic_id = #{comicId}")
    int updateGlobalOrderToTemporaryNegative(@Param("comicId") Long comicId);

    @Update("UPDATE chapter SET page_count = #{pageCount} WHERE id = #{chapterId}")
    int updatePageCount(@Param("chapterId") Long chapterId, @Param("pageCount") int pageCount);

    /**
     * 元数据刷新统计批量 UPDATE：按 id 一次性更新各章节 page_count（CASE WHEN 单条 UPDATE），
     * 消除逐章 {@code update} 的往返开销。
     *
     * @param chapterList 待更新章节列表（id/pageCount 非空，须非空；含空由调用方跳过）
     * @return 受影响行数（应等于 chapterList.size()）
     */
    int updatePageCountBatch(@Param("chapterList") List<Chapter> chapterList);
}
