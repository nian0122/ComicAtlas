package com.comicatlas.api.exporter.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.comicatlas.api.exporter.persistence.entity.ExportTask;
import com.comicatlas.api.exporter.enums.ExportTaskStatus;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Select;
import java.util.List;

@Mapper
public interface ExportTaskMapper extends BaseMapper<ExportTask> {

    @Select("SELECT * FROM export_task WHERE comic_id = #{comicId} ORDER BY created_at DESC")
    List<ExportTask> selectByComicIdOrderByCreatedAtDesc(@Param("comicId") Long comicId);

    @Select("SELECT * FROM export_task ORDER BY created_at DESC")
    List<ExportTask> selectAllOrderByCreatedAtDesc();

    @Select("SELECT * FROM export_task WHERE comic_id = #{comicId} "
            + "AND status IN ('PENDING', 'RUNNING') LIMIT 1")
    ExportTask selectActiveByComicId(@Param("comicId") Long comicId);

    @Select("SELECT * FROM export_task WHERE management_task_id = #{managementTaskId} LIMIT 1")
    ExportTask selectByManagementTaskId(@Param("managementTaskId") Long managementTaskId);

    /**
     * 将导出任务恢复为可执行的初始状态。
     */
    @Update("UPDATE export_task SET status = #{pendingStatus}, progress = 0, error_msg = NULL, completed_at = NULL WHERE id = #{exportTaskId}")
    int resetForRetry(@Param("exportTaskId") Long exportTaskId,
                      @Param("pendingStatus") ExportTaskStatus pendingStatus);
}
