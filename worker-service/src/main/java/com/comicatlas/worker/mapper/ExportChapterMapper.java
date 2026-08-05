package com.comicatlas.worker.mapper;

import com.comicatlas.worker.entity.ExportChapter;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ExportChapterMapper {

    @Select("SELECT id, comic_id, catalog_id, title, chapter_no, global_order FROM chapter WHERE comic_id = #{comicId} ORDER BY global_order ASC")
    List<ExportChapter> selectByComicIdOrderByGlobalOrder(Long comicId);
}
