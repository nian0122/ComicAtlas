package com.comicatlas.worker.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.comicatlas.worker.entity.ExportMedia;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ExportMediaMapper extends BaseMapper<ExportMedia> {

    @Select("""
        SELECT p.id, p.chapter_id, p.page_number, p.media_type,
               p.hq_root, p.hq_path, p.hq_status,
               p.lq_root, p.lq_path, p.lq_status,
               p.file_size, p.width, p.height,
               p.duration, p.container, p.video_codec, p.audio_codec
        FROM page p
        JOIN chapter ch ON p.chapter_id = ch.id
        WHERE ch.comic_id = #{comicId}
        ORDER BY ch.global_order ASC, p.page_number ASC
    """)
    List<ExportMedia> selectByComicId(Long comicId);
}
