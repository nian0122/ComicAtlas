package com.comicatlas.worker.exporter.persistence;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 导出专用只读标签查询，Worker 不复用 API 的写模型。 */
@Mapper
public interface ExportTagMapper {

    @Select("SELECT t.name FROM comic_tag ct JOIN tag t ON t.id = ct.tag_id "
            + "WHERE ct.comic_id = #{comicId} ORDER BY t.name")
    List<String> selectNamesByComicId(Long comicId);
}
