package com.comicatlas.api.recovery.persistence.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 恢复兼容流程所需的跨域只读统计查询。 */
@Mapper
public interface RecoveryDataMapper {

    @Select("<script>SELECT COUNT(*) FROM import_task WHERE comic_id = #{comicId} AND status IN <foreach collection='statuses' item='status' open='(' separator=',' close=')'>#{status}</foreach></script>")
    long countImportTasks(@Param("comicId") Long comicId, @Param("statuses") List<String> statuses);

    @Select("SELECT COUNT(*) FROM comic_tag WHERE comic_id = #{comicId}")
    long countComicTags(@Param("comicId") Long comicId);

    @Select("SELECT COUNT(*) FROM reading_history WHERE comic_id = #{comicId}")
    long countReadingHistory(@Param("comicId") Long comicId);

    @Select("<script>SELECT COUNT(*) FROM page WHERE chapter_id IN <foreach collection='chapterIds' item='chapterId' open='(' separator=',' close=')'>#{chapterId}</foreach></script>")
    long countMediaByChapterIds(@Param("chapterIds") List<Long> chapterIds);
}
