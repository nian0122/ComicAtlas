package com.comicatlas.api.exporter.service;

import com.comicatlas.api.exporter.dto.ExportArtifactVO;
import com.comicatlas.api.exporter.dto.ExportTaskVO;
import java.util.List;

/** 导出任务操作服务契约。 */
public interface ExportOperationService {
    ExportTaskVO createExportTask(Long comicId);
    ExportTaskVO createExportTask(Long comicId, String format);
    List<ExportTaskVO> listExports(Long comicId);
    List<ExportTaskVO> listAllExports();
    ExportTaskVO getTask(Long taskId);
    List<ExportArtifactVO> listArtifacts(Long taskId);
}
