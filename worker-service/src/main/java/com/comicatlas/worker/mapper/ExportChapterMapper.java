package com.comicatlas.worker.mapper;

import com.comicatlas.worker.entity.ExportChapter;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ExportChapterMapper {

    @Select("SELECT id, comic_id, catalog_id, title, chapter_no, global_order FROM chapter WHERE comic_id = #{comicId} ORDER BY global_order ASC")
    List<ExportChapter> selectByComicIdOrderByGlobalOrder(Long comicId);

    /**
     * 元数据扫盘刷新专用只读查询：额外取章节乐观锁 version 作为快照基线。
     * 不修改既有查询，避免影响导出/删除等共享消费方。
     */
    @Select("SELECT id, comic_id, catalog_id, title, chapter_no, global_order, version FROM chapter WHERE comic_id = #{comicId} ORDER BY global_order ASC")
    List<ExportChapter> selectByComicIdWithVersion(Long comicId);
}
