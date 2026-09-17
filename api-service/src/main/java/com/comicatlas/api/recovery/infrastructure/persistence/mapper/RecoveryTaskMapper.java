package com.comicatlas.api.recovery.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.comicatlas.api.recovery.infrastructure.persistence.entity.RecoveryTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface RecoveryTaskMapper extends BaseMapper<RecoveryTask> {
    @Select("SELECT id, status, created_at, started_at, ended_at, retry_count, total_comics, recovered_comics, skipped_comics, placeholder_comics, error_comics, error_message, error_details, management_task_id FROM recovery_task ORDER BY created_at DESC")
    IPage<RecoveryTask> selectPageOrderByCreatedAtDesc(IPage<RecoveryTask> page);

    @Select("SELECT COUNT(*) FROM recovery_task WHERE status IN ('RUNNING', 'QUEUED')")
    long countActiveTasks();
}
