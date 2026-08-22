package com.comicatlas.worker.exporter.persistence;

import com.comicatlas.worker.exporter.persistence.ExportMedia;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ExportMediaMapper {

    @Select("""
        SELECT p.id, p.chapter_id, p.page_number, p.media_type,
               p.hq_root, p.hq_path, p.hq_status,
               p.lq_root, p.lq_path, p.lq_status, p.lq_size,
               p.hq_size, p.width, p.height,
               p.duration, p.container, p.video_codec, p.audio_codec
        FROM page p
        JOIN chapter ch ON p.chapter_id = ch.id
        WHERE ch.comic_id = #{comicId}
        ORDER BY ch.global_order ASC, p.page_number ASC
    """)
    List<ExportMedia> selectByComicId(Long comicId);

    /**
     * 元数据扫盘刷新专用只读查询：额外取媒体生命周期 status 与乐观锁 version 作为快照基线。
     * 不修改既有查询，避免影响导出/删除等共享消费方。
     */
    @Select("""
        SELECT p.id, p.chapter_id, p.page_number, p.media_type,
               p.hq_root, p.hq_path, p.hq_status,
               p.lq_root, p.lq_path, p.lq_status, p.lq_size,
               p.hq_size, p.width, p.height,
               p.duration, p.container, p.video_codec, p.audio_codec,
               p.status, p.version
        FROM page p
        JOIN chapter ch ON p.chapter_id = ch.id
        WHERE ch.comic_id = #{comicId}
        ORDER BY ch.global_order ASC, p.page_number ASC
    """)
    List<ExportMedia> selectByComicIdWithVersionAndStatus(Long comicId);

    @Select("""
        SELECT p.id, p.chapter_id, p.page_number, p.media_type,
               p.hq_root, p.hq_path, p.hq_status,
               p.lq_root, p.lq_path, p.lq_status, p.lq_size,
               p.hq_size, p.width, p.height,
               p.duration, p.container, p.video_codec, p.audio_codec
        FROM page p
        WHERE p.chapter_id = #{chapterId}
        ORDER BY p.page_number ASC
    """)
    List<ExportMedia> selectByChapterId(Long chapterId);

    @Select("""
        SELECT id, chapter_id, page_number, media_type,
               hq_root, hq_path, hq_status,
               lq_root, lq_path, lq_status, lq_size,
               hq_size, width, height,
               duration, container, video_codec, audio_codec
        FROM page
        WHERE id = #{id}
    """)
    ExportMedia selectById(Long id);

    @Select("""
        SELECT p.id, p.chapter_id, p.page_number, p.media_type,
               p.hq_root, p.hq_path, p.hq_status,
               p.lq_root, p.lq_path, p.lq_status, p.lq_size,
               p.hq_size, p.width, p.height,
               p.duration, p.container, p.video_codec, p.audio_codec
        FROM page p
        JOIN chapter ch ON p.chapter_id = ch.id
        WHERE ch.comic_id = #{comicId}
          AND p.media_type = 'VIDEO'
          AND p.width IS NULL
        ORDER BY ch.global_order ASC, p.page_number ASC
    """)
    List<ExportMedia> selectVideosMissingMetadataByComicId(Long comicId);
}

