package com.comicatlas.worker.mapper;

import com.comicatlas.worker.entity.ExportComic;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ExportComicMapper {

    @Select("SELECT id, title, author, category, status, cover_path FROM comic WHERE id = #{id}")
    ExportComic selectById(Long id);
}
