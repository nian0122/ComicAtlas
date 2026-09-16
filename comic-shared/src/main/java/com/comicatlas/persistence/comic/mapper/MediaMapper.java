package com.comicatlas.persistence.comic.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.comicatlas.persistence.comic.entity.Media;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 媒体页 Mapper。
 * <p>
 * {@link #insertImportBatch(List)} 为导入落库专用批量插入（MySQL 多值 INSERT，
 * 见 {@code mapper/MediaMapper.xml}），用于消除逐页 {@link #insert} 的往返开销。
 */
@Mapper
public interface MediaMapper extends BaseMapper<Media> {

    /** 将章节内媒体页码临时置为互不冲突的负值，供重排第二阶段写回。 */
    default int updatePageNumberToTemporaryNegative(Long chapterId) {
        return update(null, new LambdaUpdateWrapper<Media>()
                .eq(Media::getChapterId, chapterId)
                .setSql("page_number = -id"));
    }

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
