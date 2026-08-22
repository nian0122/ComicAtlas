package com.comicatlas.worker.exporter.persistence;

import com.comicatlas.worker.exporter.persistence.ExportUploadSession;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ExportUploadSessionMapper {

    @Select("SELECT id, session_id, comic_id, chapter_id, replace_media_id, status FROM upload_session WHERE id = #{id}")
    ExportUploadSession selectById(Long id);
}
