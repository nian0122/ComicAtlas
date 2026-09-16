package com.comicatlas.api.exporter.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.comicatlas.api.exporter.persistence.entity.ExportTask;
import com.comicatlas.api.exporter.enums.ExportTaskStatus;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ExportTaskMapper extends BaseMapper<ExportTask> {

    /**
     * 将导出任务恢复为可执行的初始状态。
     */
    @Update("UPDATE export_task SET status = #{pendingStatus}, progress = 0, error_msg = NULL, completed_at = NULL WHERE id = #{exportTaskId}")
    int resetForRetry(@Param("exportTaskId") Long exportTaskId,
                      @Param("pendingStatus") ExportTaskStatus pendingStatus);
}
