package com.comicatlas.api.task.persistence.mapper;

import com.comicatlas.api.exporter.persistence.entity.ExportTask;
import com.comicatlas.api.importer.persistence.entity.DirectoryScanTask;
import com.comicatlas.api.importer.persistence.entity.ImportTask;
import com.comicatlas.api.recovery.persistence.entity.RecoveryTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 历史任务回填只读查询，避免任务业务层依赖条件构造器。 */
@Mapper
public interface LegacyTaskMapper {

    @Select("SELECT * FROM import_task WHERE management_task_id IS NULL")
    List<ImportTask> selectUnboundImports();

    @Select("SELECT * FROM recovery_task WHERE management_task_id IS NULL")
    List<RecoveryTask> selectUnboundRecoveries();

    @Select("SELECT * FROM export_task WHERE management_task_id IS NULL")
    List<ExportTask> selectUnboundExports();

    @Select("SELECT * FROM directory_scan_task WHERE management_task_id IS NULL")
    List<DirectoryScanTask> selectUnboundScans();
}
