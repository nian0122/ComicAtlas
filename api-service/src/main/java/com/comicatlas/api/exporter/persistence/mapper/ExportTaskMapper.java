package com.comicatlas.api.exporter.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.comicatlas.api.exporter.persistence.entity.ExportTask;
import com.comicatlas.api.exporter.enums.ExportTaskStatus;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ExportTaskMapper extends BaseMapper<ExportTask> {

    /**
     * 将导出任务恢复为可执行的初始状态。
     */
    default int resetForRetry(Long exportTaskId, ExportTaskStatus pendingStatus) {
        return update(null, new LambdaUpdateWrapper<ExportTask>()
                .eq(ExportTask::getId, exportTaskId)
                .set(ExportTask::getStatus, pendingStatus)
                .set(ExportTask::getProgress, 0)
                .set(ExportTask::getErrorMsg, null)
                .set(ExportTask::getCompletedAt, null));
    }
}
