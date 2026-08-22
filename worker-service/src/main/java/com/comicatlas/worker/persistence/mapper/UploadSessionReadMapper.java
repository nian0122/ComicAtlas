package com.comicatlas.worker.persistence.mapper;

import com.comicatlas.worker.persistence.record.UploadSessionRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UploadSessionReadMapper {

    @Select("SELECT id, session_id, comic_id, chapter_id, replace_media_id, status FROM upload_session WHERE id = #{id}")
    UploadSessionRecord selectById(Long id);
}
