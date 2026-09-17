package com.comicatlas.api.importer.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.comicatlas.api.importer.infrastructure.persistence.entity.ImportTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.comicatlas.api.importer.domain.model.ImportTaskStatus;
@Mapper
public interface ImportTaskMapper extends BaseMapper<ImportTask> {

    @Select("SELECT id, management_task_id, comic_id, source_ref, source_type, source_path, batch_id, status, progress, total_pages, downloaded_pages, download_method, download_speed, eta_seconds, error_message, retry_count, start_time, end_time, duration_ms, created_at, updated_at FROM import_task WHERE management_task_id = #{managementTaskId} LIMIT 1")
    ImportTask selectByManagementTaskId(@Param("managementTaskId") Long managementTaskId);

            @Select({"<script>", "SELECT id, management_task_id, comic_id, source_ref, source_type, source_path, batch_id, status, progress, total_pages, downloaded_pages, download_method, download_speed, eta_seconds, error_message, retry_count, start_time, end_time, duration_ms, created_at, updated_at FROM import_task", "<where>",
            "<if test='status != null'>AND status = #{status}</if>",
            "<if test='batchId != null'>AND batch_id = #{batchId}</if>",
            "</where> ORDER BY created_at DESC", "</script>"})
    IPage<ImportTask> selectPageByConditions(Page<ImportTask> page,
                                             @Param("status") ImportTaskStatus status,
                                             @Param("batchId") String batchId);
}
