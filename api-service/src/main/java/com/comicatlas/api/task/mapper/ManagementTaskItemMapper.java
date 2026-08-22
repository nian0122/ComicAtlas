package com.comicatlas.api.task.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.comicatlas.api.task.entity.ManagementTaskItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 管理任务目标项 Mapper。
 */
@Mapper
public interface ManagementTaskItemMapper extends BaseMapper<ManagementTaskItem> {

    /**
     * 查询归属指定目标的任务 ID 列表（去重）。
     *
     * <p>归属规则：item 直接命中目标 id，或 item 目标（章节/媒体）归属于该目标。
     * 漫画级操作（LQ/HQ/转码）创建的任务项是章节/媒体级，字面 target_id 反查会漏掉父漫画，
     * 必须把 CHAPTER→chapter.comic_id、MEDIA→page.chapter.comic_id 解析进归属，
     * 否则"该漫画全部任务"类统计缺失。
     *
     * @param targetId 目标 ID（通常为漫画 ID）
     * @return 归属该目标的任务 ID 列表（已去重，可为空）
     */
    List<Long> selectTaskIdsByTarget(@Param("targetId") Long targetId);
}
