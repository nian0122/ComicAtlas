package com.comicatlas.persistence.comic.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.comicatlas.persistence.comic.entity.Media;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 媒体页 Mapper。
 * <p>
 * {@link #insertImportBatch(List)} 为导入落库专用批量插入（MySQL 多值 INSERT，
 * 见 {@code mapper/MediaMapper.xml}），用于消除逐页 {@link #insert} 的往返开销。
 */
@Mapper
public interface MediaMapper extends BaseMapper<Media> {

    @Select("SELECT id, chapter_id, page_number FROM page WHERE chapter_id = #{chapterId} ORDER BY page_number ASC")
    List<Media> selectPageNumbersByChapterId(@Param("chapterId") Long chapterId);

    @Select("SELECT id, chapter_id, page_number, hq_root, hq_path, lq_root, lq_path, hq_status, lq_status, status, lq_size, width, height, hq_size, media_type, duration, container, video_codec, audio_codec FROM page WHERE chapter_id = #{chapterId} AND status = 'READY' ORDER BY page_number ASC")
    List<Media> selectReadyByChapterIdForManagement(@Param("chapterId") Long chapterId);

    @Select("<script>SELECT COUNT(*) FROM page WHERE chapter_id IN <foreach collection='chapterIds' item='chapterId' open='(' separator=',' close=')'>#{chapterId}</foreach></script>")
    long countByChapterIds(@Param("chapterIds") List<Long> chapterIds);

    @org.apache.ibatis.annotations.Delete("<script>DELETE FROM page WHERE chapter_id IN <foreach collection='chapterIds' item='chapterId' open='(' separator=',' close=')'>#{chapterId}</foreach></script>")
    int deleteByChapterIds(@Param("chapterIds") List<Long> chapterIds);

    @org.apache.ibatis.annotations.Delete("DELETE FROM page WHERE chapter_id = #{chapterId}")
    int deleteByChapterId(@Param("chapterId") Long chapterId);

    @Select("SELECT id FROM page WHERE chapter_id = #{chapterId} AND media_type = 'IMAGE' "
            + "AND hq_status IN ('READY', 'DELETE_QUEUED', 'DELETING', 'MISSING')")
    List<Media> selectHqDeleteCandidates(@Param("chapterId") Long chapterId);

    @Select("SELECT id, chapter_id, page_number, hq_root, hq_path, lq_root, lq_path, hq_status, lq_status, "
            + "transcode_status, status, lq_size, width, height, hq_size, media_type, duration, container, "
            + "video_codec, audio_codec FROM page WHERE chapter_id = #{chapterId} AND status = 'READY' "
            + "ORDER BY page_number ASC")
    List<Media> selectReadyByChapterId(@Param("chapterId") Long chapterId);

    @Select({"<script>",
            "SELECT COUNT(*) FROM page WHERE chapter_id IN",
            "<foreach collection='chapterIds' item='chapterId' open='(' separator=',' close=')'>#{chapterId}</foreach>",
            "AND hq_status &lt;&gt; #{readyStatus}",
            "</script>"})
    long countByChapterIdsAndHqStatusNot(@Param("chapterIds") List<Long> chapterIds,
                                         @Param("readyStatus") String readyStatus);

    @Select({"<script>",
            "SELECT id, chapter_id, page_number, hq_root, hq_path, lq_root, lq_path, hq_status, lq_status,",
            "transcode_status, status, lq_size, width, height, hq_size, media_type, duration, container,",
            "video_codec, audio_codec FROM page WHERE chapter_id IN",
            "<foreach collection='chapterIds' item='chapterId' open='(' separator=',' close=')'>#{chapterId}</foreach>",
            "</script>"})
    List<Media> selectAllByChapterIds(@Param("chapterIds") List<Long> chapterIds);

    @Select({"<script>",
            "SELECT id, chapter_id, page_number, hq_root, hq_path, lq_root, lq_path, hq_status, lq_status,",
            "transcode_status, status, lq_size, width, height, hq_size, media_type, duration, container,",
            "video_codec, audio_codec FROM page WHERE chapter_id IN",
            "<foreach collection='chapterIds' item='chapterId' open='(' separator=',' close=')'>#{chapterId}</foreach>",
            "AND status NOT IN",
            "<foreach collection='inactiveStatuses' item='status' open='(' separator=',' close=')'>#{status}</foreach>",
            "</script>"})
    List<Media> selectActiveByChapterIds(@Param("chapterIds") List<Long> chapterIds,
                                         @Param("inactiveStatuses") List<String> inactiveStatuses);

    @Select("SELECT id, chapter_id, page_number, hq_root, hq_path, lq_root, lq_path, hq_status, lq_status, "
            + "transcode_status, status, lq_size, width, height, hq_size, media_type, duration, container, "
            + "video_codec, audio_codec FROM page WHERE chapter_id = #{chapterId} ORDER BY page_number ASC")
    List<Media> selectByChapterId(@Param("chapterId") Long chapterId);

    @Select("SELECT id, chapter_id, page_number, hq_root, hq_path, lq_root, lq_path, hq_status, lq_status, "
            + "transcode_status, status, lq_size, width, height, hq_size, media_type, duration, container, "
            + "video_codec, audio_codec FROM page WHERE chapter_id = #{chapterId} AND media_type = 'IMAGE' "
            + "ORDER BY page_number ASC")
    List<Media> selectImagesByChapterId(@Param("chapterId") Long chapterId);

    @Select("<script>SELECT id, chapter_id, page_number, hq_root, hq_path, lq_root, lq_path, hq_status, lq_status, "
            + "transcode_status, status, lq_size, width, height, hq_size, media_type, duration, container, video_codec, audio_codec "
            + "FROM page WHERE chapter_id IN <foreach collection='chapterIds' item='chapterId' open='(' separator=',' close=')'>#{chapterId}</foreach> "
            + "ORDER BY chapter_id, page_number</script>")
    List<Media> selectByChapterIds(@Param("chapterIds") List<Long> chapterIds);

    @Select("<script>SELECT id, chapter_id, page_number, hq_root, hq_path, lq_root, lq_path, hq_status, lq_status, "
            + "transcode_status, status, lq_size, width, height, hq_size, media_type, duration, container, video_codec, audio_codec "
            + "FROM page WHERE chapter_id IN <foreach collection='chapterIds' item='chapterId' open='(' separator=',' close=')'>#{chapterId}</foreach> "
            + "AND media_type = 'IMAGE' AND hq_status IN ('READY','MISSING') ORDER BY chapter_id, page_number</script>")
    List<Media> selectDeletableImagesByChapterIds(@Param("chapterIds") List<Long> chapterIds);

    @Select("SELECT COUNT(*) FROM page WHERE chapter_id = #{chapterId} AND media_type = 'IMAGE' AND hq_status IN ('READY','MISSING')")
    long countDeletableImagesByChapterId(@Param("chapterId") Long chapterId);

    @Select("<script>SELECT id, chapter_id, page_number, hq_root, hq_path, lq_root, lq_status, hq_status, "
            + "transcode_status, status, lq_size, width, height, hq_size, media_type, duration, container, video_codec, audio_codec "
            + "FROM page WHERE chapter_id IN <foreach collection='chapterIds' item='chapterId' open='(' separator=',' close=')'>#{chapterId}</foreach> "
            + "AND media_type = 'VIDEO' ORDER BY chapter_id, page_number</script>")
    List<Media> selectVideosByChapterIds(@Param("chapterIds") List<Long> chapterIds);

    @Select("SELECT id, chapter_id, page_number, hq_root, hq_path, lq_root, lq_path, hq_status, lq_status, transcode_status, status, lq_size, width, height, hq_size, media_type, duration, container, video_codec, audio_codec FROM page WHERE chapter_id = #{chapterId} AND media_type = 'VIDEO' ORDER BY page_number")
    List<Media> selectVideosByChapterId(@Param("chapterId") Long chapterId);

    @Select("SELECT COUNT(*) FROM page WHERE chapter_id = #{chapterId} AND status NOT IN ('DELETED','TRASHED')")
    long countActiveByChapterId(@Param("chapterId") Long chapterId);

    @Select("<script>SELECT COUNT(*) FROM page WHERE chapter_id IN <foreach collection='chapterIds' item='chapterId' open='(' separator=',' close=')'>#{chapterId}</foreach> AND status NOT IN ('DELETED','TRASHED')</script>")
    long countActiveByChapterIds(@Param("chapterIds") List<Long> chapterIds);

    @Update("UPDATE page SET lq_status = 'QUEUED' WHERE chapter_id = #{chapterId} AND media_type = 'IMAGE' AND hq_status <> 'DELETED'")
    int markLqQueued(@Param("chapterId") Long chapterId);

    @Update("UPDATE page SET transcode_status = 'NOT_NEEDED' WHERE id = #{mediaId} AND transcode_status = 'REQUIRED'")
    int markTranscodeNotNeeded(@Param("mediaId") Long mediaId);

    @Update("UPDATE page SET transcode_status = 'QUEUED' WHERE id = #{mediaId}")
    int markTranscodeQueued(@Param("mediaId") Long mediaId);

    @Update("UPDATE page SET hq_status = 'DELETED', hq_root = NULL, hq_path = NULL WHERE id = #{mediaId}")
    int markHqDeleted(@Param("mediaId") Long mediaId);

    @Update("UPDATE page SET hq_status = 'FAILED' WHERE chapter_id = #{chapterId} AND hq_status IN ('DELETE_QUEUED', 'DELETING')")
    int markHqDeleteFailed(@Param("chapterId") Long chapterId);

    @Update("UPDATE page SET transcode_status = 'FAILED' WHERE id = #{mediaId} AND transcode_status IN ('QUEUED', 'TRANSCODING')")
    int markTranscodeFailed(@Param("mediaId") Long mediaId);

    @Update("UPDATE page SET lq_status = 'GENERATING' WHERE chapter_id = #{chapterId} AND lq_status = 'QUEUED'")
    int transitionLqGenerating(@Param("chapterId") Long chapterId);

    @Update("UPDATE page SET hq_status = 'DELETING' WHERE chapter_id = #{chapterId} AND hq_status = 'DELETE_QUEUED'")
    int transitionHqDeleting(@Param("chapterId") Long chapterId);

    @Update("UPDATE page SET transcode_status = 'TRANSCODING' WHERE id = #{mediaId} AND transcode_status = 'QUEUED'")
    int transitionTranscoding(@Param("mediaId") Long mediaId);

    int markHqDeleteQueued(@Param("chapterIds") List<Long> chapterIds);

    int markHqDeleteQueuedByChapter(@Param("chapterId") Long chapterId);

    int applyTranscodeCompleted(@Param("mediaId") Long mediaId,
                                @Param("container") String container,
                                @Param("videoCodec") String videoCodec,
                                @Param("audioCodec") String audioCodec,
                                @Param("duration") BigDecimal duration,
                                @Param("hqSize") Long hqSize,
                                @Param("hqPath") String hqPath);

    int markTrashed(@Param("mediaId") Long mediaId, @Param("trashedAt") LocalDateTime trashedAt,
                    @Param("hqRoot") String hqRoot, @Param("hqPath") String hqPath);

    @Update("UPDATE page SET status = 'READY', hq_status = 'READY', hq_root = #{hqRoot}, hq_path = #{hqPath}, page_number = #{pageNumber}, trashed_at = NULL WHERE id = #{mediaId} AND status = 'RESTORING'")
    int markRestored(@Param("mediaId") Long mediaId, @Param("hqRoot") String hqRoot,
                     @Param("hqPath") String hqPath, @Param("pageNumber") int pageNumber);

    /** 将章节内媒体页码临时置为互不冲突的负值，供重排第二阶段写回。 */
    @Update("UPDATE page SET page_number = -id WHERE chapter_id = #{chapterId}")
    int updatePageNumberToTemporaryNegative(@Param("chapterId") Long chapterId);

    /** 按章节且仅匹配旧前缀重写 HQ 路径。 */
    @Update("UPDATE page SET hq_path = REPLACE(hq_path, #{oldPrefix}, #{newPrefix}) WHERE chapter_id = #{chapterId} AND hq_path LIKE CONCAT(#{oldPrefix}, '%')")
    int normalizeLegacyHqPath(@Param("chapterId") Long chapterId,
                              @Param("oldPrefix") String oldPrefix,
                              @Param("newPrefix") String newPrefix);

    /** 按章节且仅匹配旧前缀重写 LQ 路径。 */
    @Update("UPDATE page SET lq_path = REPLACE(lq_path, #{oldPrefix}, #{newPrefix}) WHERE chapter_id = #{chapterId} AND lq_path IS NOT NULL AND lq_path LIKE CONCAT(#{oldPrefix}, '%')")
    int normalizeLegacyLqPath(@Param("chapterId") Long chapterId,
                              @Param("oldPrefix") String oldPrefix,
                              @Param("newPrefix") String newPrefix);

    /** 应用上传分析结果，并在替换场景重置派生媒体状态。 */
    int applyUploadCompleted(@Param("media") Media media, @Param("replace") boolean replace);

    /**
     * 导入落库专用批量 INSERT：一次插入多条 page 记录（多值 VALUES）。
     * 仅写入导入所需列；id/version/created_at/lq_size 使用数据库默认值。
     * 状态字段须在调用侧显式设置（PENDING/STAGING/NOT_GENERATED/NOT_NEEDED）。
     *
     * @param mediaList 待插入媒体列表（须非空；含空由调用方跳过）
     * @return 实际插入行数（应等于 mediaList.size()）
     */
    int insertImportBatch(@Param("mediaList") List<Media> mediaList);

    /**
     * 最终化确认批量 UPDATE：按章节把 PENDING/STAGING 媒体一次性置为 READY，
     * 并将 hq_path 重写为事件返回的 targetDir 相对路径（防御目录不一致边界）。
     * 幂等由 WHERE 条件保证（已 READY 行不命中，不重复自增 version）。
     *
     * @param chapterId         章节 ID
     * @param targetDirHqRelative 目标目录相对 HQ 根的路径（如 {@code {comicId}/{chapterId}}）
     * @return 受影响行数（本次实际从非 READY 转 READY 的媒体数）
     */
    int markImportFinalizedByChapter(@Param("chapterId") Long chapterId,
                                     @Param("targetDirHqRelative") String targetDirHqRelative);

    /**
     * 元数据刷新合并专用批量 UPDATE：按 id 一次性更新扫描所得的媒体字段
     * （hq_status/hq_size/width/height/media_type/duration/container/video_codec/audio_codec），
     * 消除逐行 {@code updateById} 的往返开销（CASE WHEN 单条 UPDATE）。
     * 乐观锁语义：version 统一自增；版本漂移已在 {@code applyValidatedSnapshot} 合并计划阶段校验。
     *
     * @param mediaList 待更新媒体列表（id 非空，须非空；含空由调用方跳过）
     * @return 受影响行数（应等于 mediaList.size()）
     */
    int updateRefreshBatch(@Param("mediaList") List<Media> mediaList);

    /**
     * LQ 结果专用批量 UPDATE：按媒体 ID 批量写入 READY、LQ 根、WebP 路径和大小。
     * chapterId 作为额外边界，防止错误事件跨章节更新页面。
     *
     * @param chapterId 章节 ID
     * @param mediaList 已确认存在 LQ 产物的媒体列表（须非空）
     * @return 实际更新行数
     */
    int updateLqReadyBatch(@Param("chapterId") Long chapterId,
                           @Param("mediaList") List<Media> mediaList);

    /** LQ 完成回写前，将本章图片统一重置为未生成。 */
    int resetLqNotGeneratedByChapter(@Param("chapterId") Long chapterId);

    /** LQ 失败回写时，将本章仍处于排队或生成中的图片统一置为失败。 */
    int markLqFailedByChapter(@Param("chapterId") Long chapterId);
}
