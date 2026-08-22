package com.comicatlas.worker.persistence.mapper;

import com.comicatlas.worker.persistence.record.ChapterRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ChapterReadMapper {

    @Select("SELECT id, comic_id, catalog_id, title, chapter_no, global_order FROM chapter WHERE comic_id = #{comicId} ORDER BY global_order ASC")
    List<ChapterRecord> selectByComicIdOrderByGlobalOrder(Long comicId);

    /**
     * 元数据扫盘刷新专用只读查询：额外取章节乐观锁 version 作为快照基线。
     * 不修改既有查询，避免影响导出/删除等共享消费方。
     */
    @Select("SELECT id, comic_id, catalog_id, title, chapter_no, global_order, version FROM chapter WHERE comic_id = #{comicId} ORDER BY global_order ASC")
    List<ChapterRecord> selectByComicIdWithVersion(Long comicId);

    /**
     * 最终化陈旧事件保护专用只读查询：章节必须仍存在且属于本漫画，
     * 否则旧 attempt 的最终化事件不得再移动文件（防止搬入重试后已不存在的孤儿目录）。
     */
    @Select("SELECT COUNT(1) FROM chapter WHERE id = #{id} AND comic_id = #{comicId}")
    int countByIdAndComicId(@Param("id") Long id, @Param("comicId") Long comicId);
}
