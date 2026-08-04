package com.comicatlas.worker.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.comicatlas.worker.entity.ExportUploadFile;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ExportUploadFileMapper extends BaseMapper<ExportUploadFile> {

    @Select("""
        SELECT uf.id, uf.session_id, uf.file_id, uf.storage_name,
               uf.size_bytes, uf.sha256, uf.media_id
        FROM upload_file uf
        WHERE uf.session_id = #{sessionId}
        ORDER BY uf.id ASC
    """)
    List<ExportUploadFile> selectBySessionId(Long sessionId);
}
