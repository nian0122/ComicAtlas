package com.comicatlas.worker.persistence.mapper;

import com.comicatlas.worker.persistence.record.ComicRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ComicReadMapper {

    @Select("SELECT id, title, author, description, category, status, cover_path FROM comic WHERE id = #{id}")
    ComicRecord selectById(Long id);
}
