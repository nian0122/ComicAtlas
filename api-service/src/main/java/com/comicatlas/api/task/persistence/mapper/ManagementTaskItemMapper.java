package com.comicatlas.api.task.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.comicatlas.api.task.persistence.entity.ManagementTaskItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.time.LocalDateTime;

/**
 * 管理任务目标项 Mapper。
 */
@Mapper
public interface ManagementTaskItemMapper extends BaseMapper<ManagementTaskItem> {

    @Update("UPDATE management_task_item SET status = 'SUCCEEDED', completed_at = #{completedAt}, lock_key = NULL, updated_at = #{updatedAt} WHERE id = #{itemId} AND attempt = #{attempt} AND status NOT IN ('CANCELLED', 'SUCCEEDED', 'PARTIALLY_SUCCEEDED', 'FAILED')")
    int markSucceededIfActive(@Param("itemId") Long itemId, @Param("attempt") int attempt,
                              @Param("completedAt") LocalDateTime completedAt,
                              @Param("updatedAt") LocalDateTime updatedAt);

    @Update("UPDATE management_task_item SET status = 'FAILED', error_message = #{errorMessage}, completed_at = #{completedAt}, lock_key = NULL, updated_at = #{updatedAt} WHERE id = #{itemId} AND attempt = #{attempt} AND status NOT IN ('CANCELLED', 'SUCCEEDED', 'PARTIALLY_SUCCEEDED', 'FAILED')")
    int markFailedIfActive(@Param("itemId") Long itemId, @Param("attempt") int attempt,
                           @Param("errorMessage") String errorMessage,
                           @Param("completedAt") LocalDateTime completedAt,
                           @Param("updatedAt") LocalDateTime updatedAt);

    /** 绑定回收清单引用，供后续 Worker 命令读取。 */
    @Update("UPDATE management_task_item SET result_ref_type = 'TRASH_MANIFEST', result_ref_id = #{manifestTaskId} WHERE id = #{itemId}")
    int bindTrashManifest(@Param("itemId") Long itemId, @Param("manifestTaskId") Long manifestTaskId);

    /**
     * 查询归属指定漫画的任务 ID 列表（去重）。
     *
     * <p>归属规则：COMIC item 直接命中漫画 id，或 item 目标（章节/媒体）归属于该漫画。
     * 漫画级操作（LQ/HQ/转码）创建的任务项是章节/媒体级，字面 target_id 反查会漏掉父漫画，
     * 必须把 CHAPTER→chapter.comic_id、MEDIA→page.chapter.comic_id 解析进归属，
     * 否则"该漫画全部任务"类统计缺失。
     *
     * @param comicId 漫画 ID
     * @return 归属该漫画的任务 ID 列表（已去重，可为空）
     */
    List<Long> selectTaskIdsByComicId(@Param("comicId") Long comicId);

    /**
     * 统计指定漫画在元数据刷新任务中尚未终态的任务项。
     *
     * @param taskId 任务 ID
     * @param comicId 漫画 ID
     * @return 活跃任务项数量
     */
    long countActiveMetadataItems(@Param("taskId") Long taskId, @Param("comicId") Long comicId);
}
