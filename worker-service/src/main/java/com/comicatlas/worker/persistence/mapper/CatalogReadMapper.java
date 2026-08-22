package com.comicatlas.worker.persistence.mapper;

import com.comicatlas.worker.persistence.record.CatalogRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface CatalogReadMapper {

    @Select("SELECT id, comic_id, parent_id, title, sort_order FROM catalog WHERE comic_id = #{comicId} ORDER BY sort_order ASC")
    List<CatalogRecord> selectByComicId(Long comicId);
}
