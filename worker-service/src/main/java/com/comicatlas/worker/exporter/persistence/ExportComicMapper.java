package com.comicatlas.worker.exporter.persistence;

import com.comicatlas.worker.exporter.persistence.ExportComic;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ExportComicMapper {

    @Select("SELECT id, title, author, description, category, status, cover_path FROM comic WHERE id = #{id}")
    ExportComic selectById(Long id);
}
