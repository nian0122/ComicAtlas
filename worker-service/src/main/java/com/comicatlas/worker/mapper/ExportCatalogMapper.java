package com.comicatlas.worker.mapper;

import com.comicatlas.worker.entity.ExportCatalog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ExportCatalogMapper {

    @Select("SELECT id, comic_id, parent_id, title, sort_order FROM catalog WHERE comic_id = #{comicId} ORDER BY sort_order ASC")
    List<ExportCatalog> selectByComicId(Long comicId);
}
