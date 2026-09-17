package com.comicatlas.api.trash.infrastructure.persistence.mapper;

import com.comicatlas.api.task.domain.model.TaskType;
import com.comicatlas.api.task.infrastructure.persistence.entity.ManagementTaskItem;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 回收站生命周期所需的跨实体清理与任务定位查询。 */
@Mapper
public interface TrashDataMapper {

    @Select("SELECT id, task_id, target_type, target_id, operation_type FROM management_task_item WHERE target_type = #{targetType} AND target_id = #{targetId} AND operation_type = #{operationType} ORDER BY id DESC LIMIT 1")
    ManagementTaskItem selectLatestTaskItem(@Param("targetType") String targetType, @Param("targetId") Long targetId,
                                            @Param("operationType") TaskType operationType);

    @Delete("DELETE FROM reading_history WHERE comic_id = #{comicId}")
    int deleteReadingHistoryByComicId(@Param("comicId") Long comicId);

    @Delete("DELETE FROM comic_tag WHERE comic_id = #{comicId}")
    int deleteComicTagsByComicId(@Param("comicId") Long comicId);
}
