package com.comicatlas.api.task.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.comicatlas.api.task.persistence.entity.ManagementTaskItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 管理任务目标项 Mapper。
 */
@Mapper
public interface ManagementTaskItemMapper extends BaseMapper<ManagementTaskItem> {

    /** 绑定回收清单引用，供后续 Worker 命令读取。 */
    default int bindTrashManifest(Long itemId, Long manifestTaskId) {
        return update(null, new LambdaUpdateWrapper<ManagementTaskItem>()
                .eq(ManagementTaskItem::getId, itemId)
                .set(ManagementTaskItem::getResultRefType, "TRASH_MANIFEST")
                .set(ManagementTaskItem::getResultRefId, manifestTaskId));
    }

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
