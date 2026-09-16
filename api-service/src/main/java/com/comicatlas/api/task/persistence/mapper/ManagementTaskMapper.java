package com.comicatlas.api.task.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.comicatlas.api.task.persistence.entity.ManagementTask;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 管理任务主表 Mapper。
 */
@Mapper
public interface ManagementTaskMapper extends BaseMapper<ManagementTask> {

    @Select("SELECT * FROM management_task WHERE idempotency_key = #{idempotencyKey} LIMIT 1")
    ManagementTask selectByIdempotencyKey(@Param("idempotencyKey") String idempotencyKey);

    IPage<ManagementTask> selectPageByCondition(IPage<ManagementTask> page,
            @Param("taskType") String taskType, @Param("status") String status,
            @Param("batchId") String batchId, @Param("targetType") String targetType,
            @Param("taskIds") List<Long> taskIds);

    @Update("UPDATE management_task SET status = 'QUEUED', attempt = #{attempt}, progress = 0, success_count = 0, failure_count = 0, cancelled_count = 0, error_message = NULL, error_detail = NULL, stage = NULL, started_at = NULL, completed_at = NULL, updated_at = #{updatedAt} WHERE id = #{taskId}")
    int resetForRetry(@Param("taskId") Long taskId, @Param("attempt") int attempt,
                      @Param("updatedAt") LocalDateTime updatedAt);

    int updateStage(@Param("taskId") Long taskId, @Param("stage") String stage,
                    @Param("progress") Integer progress, @Param("startTask") boolean startTask,
                    @Param("startedAt") LocalDateTime startedAt, @Param("updatedAt") LocalDateTime updatedAt);

    /**
     * 删除终态且 completed_at 超过指定天数的管理任务（级联删除 items）。
     */
    @Delete("DELETE FROM management_task WHERE status IN ('SUCCEEDED','PARTIALLY_SUCCEEDED','FAILED','CANCELLED') AND completed_at IS NOT NULL AND completed_at < DATE_SUB(NOW(), INTERVAL #{days} DAY)")
    int deleteTerminalOlderThan(@Param("days") int days);
}
