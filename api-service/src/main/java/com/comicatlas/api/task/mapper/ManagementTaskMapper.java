package com.comicatlas.api.task.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.comicatlas.api.task.entity.ManagementTask;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 管理任务主表 Mapper。
 */
@Mapper
public interface ManagementTaskMapper extends BaseMapper<ManagementTask> {

    /**
     * 删除终态且 completed_at 超过指定天数的管理任务（级联删除 items）。
     */
    @Delete("DELETE FROM management_task WHERE status IN ('SUCCEEDED','PARTIALLY_SUCCEEDED','FAILED','CANCELLED') AND completed_at IS NOT NULL AND completed_at < DATE_SUB(NOW(), INTERVAL #{days} DAY)")
    int deleteTerminalOlderThan(@Param("days") int days);
}
