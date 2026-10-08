package com.comicatlas.worker.persistence.mapper;

import com.comicatlas.worker.persistence.record.MediaRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface MediaReadMapper {

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
    List<MediaRecord> selectByComicId(Long comicId);

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
    List<MediaRecord> selectByComicIdWithVersionAndStatus(Long comicId);

    /** 章节级元数据刷新：只读取一个章节的媒体基线。 */
    @Select("""
        SELECT id, chapter_id, page_number, media_type,
               hq_root, hq_path, hq_status,
               lq_root, lq_path, lq_status, lq_size,
               hq_size, width, height,
               duration, container, video_codec, audio_codec,
               status, version
        FROM page
        WHERE chapter_id = #{chapterId}
        ORDER BY page_number ASC
    """)
    List<MediaRecord> selectByChapterIdWithVersionAndStatus(Long chapterId);

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
    List<MediaRecord> selectByChapterId(Long chapterId);

    /** LQ 专用候选查询：活动章节中的活动图片页一次取齐。 */
    @Select("""
        SELECT media_page.id, media_page.chapter_id, media_page.page_number, media_page.media_type,
               media_page.hq_root, media_page.hq_path, media_page.hq_status,
               media_page.lq_root, media_page.lq_path, media_page.lq_status, media_page.lq_size,
               media_page.hq_size, media_page.width, media_page.height, media_page.status
        FROM page media_page
        JOIN chapter chapter ON chapter.id = media_page.chapter_id
        JOIN comic comic ON comic.id = chapter.comic_id
        WHERE media_page.chapter_id = #{chapterId}
          AND comic.status = 'READY' AND chapter.status = 'READY' AND media_page.status = 'READY'
          AND media_page.media_type = 'IMAGE' AND media_page.hq_status <> 'DELETED'
        ORDER BY media_page.page_number ASC
    """)
    List<MediaRecord> selectLqCandidatesByChapterId(Long chapterId);

    /** 漫画级 LQ 批处理一次读取全部活动候选页，避免逐章节查询。 */
    @Select("""
        SELECT media_page.id, media_page.chapter_id, media_page.page_number, media_page.media_type,
               media_page.hq_root, media_page.hq_path, media_page.hq_status,
               media_page.lq_root, media_page.lq_path, media_page.lq_status, media_page.lq_size,
               media_page.hq_size, media_page.width, media_page.height, media_page.status
        FROM page media_page
        JOIN chapter chapter ON chapter.id = media_page.chapter_id
        JOIN comic comic ON comic.id = chapter.comic_id
        WHERE chapter.comic_id = #{comicId}
          AND comic.status = 'READY' AND chapter.status = 'READY' AND media_page.status = 'READY'
          AND media_page.media_type = 'IMAGE' AND media_page.hq_status <> 'DELETED'
        ORDER BY chapter.global_order ASC, media_page.page_number ASC
    """)
    List<MediaRecord> selectLqCandidatesByComicId(Long comicId);

    @Select("""
        SELECT id, chapter_id, page_number, media_type,
               hq_root, hq_path, hq_status,
               lq_root, lq_path, lq_status, lq_size,
               hq_size, width, height,
               duration, container, video_codec, audio_codec
        FROM page
        WHERE id = #{id}
    """)
    MediaRecord selectById(Long id);

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
    List<MediaRecord> selectVideosMissingMetadataByComicId(Long comicId);
}

