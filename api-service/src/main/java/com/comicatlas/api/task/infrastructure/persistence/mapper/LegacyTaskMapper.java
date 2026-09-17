package com.comicatlas.api.task.infrastructure.persistence.mapper;

import com.comicatlas.api.exporter.infrastructure.persistence.entity.ExportTask;
import com.comicatlas.api.importer.infrastructure.persistence.entity.DirectoryScanTask;
import com.comicatlas.api.importer.infrastructure.persistence.entity.ImportTask;
import com.comicatlas.api.recovery.infrastructure.persistence.entity.RecoveryTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 历史任务回填只读查询，避免任务业务层依赖条件构造器。 */
@Mapper
public interface LegacyTaskMapper {

    @Select("SELECT id, management_task_id, comic_id, source_ref, source_type, source_path, batch_id, status, progress, total_pages, downloaded_pages, download_method, download_speed, eta_seconds, error_message, retry_count, start_time, end_time, duration_ms, created_at, updated_at FROM import_task WHERE management_task_id IS NULL")
    List<ImportTask> selectUnboundImports();

    @Select("SELECT id, management_task_id, status, total_comics, recovered_comics, skipped_comics, placeholder_comics, error_comics, error_message, error_details, retry_count, created_at, started_at, ended_at FROM recovery_task WHERE management_task_id IS NULL")
    List<RecoveryTask> selectUnboundRecoveries();

    @Select("SELECT id, management_task_id, comic_id, format, status, progress, output_root, output_path, output_size, error_msg, created_at, completed_at FROM export_task WHERE management_task_id IS NULL")
    List<ExportTask> selectUnboundExports();

    @Select("SELECT id, management_task_id, status, directory_path, total_items, result_json, error_message, retry_count, created_at, started_at, ended_at FROM directory_scan_task WHERE management_task_id IS NULL")
    List<DirectoryScanTask> selectUnboundScans();
}
