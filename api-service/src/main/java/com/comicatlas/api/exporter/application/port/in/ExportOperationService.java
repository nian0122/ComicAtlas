package com.comicatlas.api.exporter.application.port.in;

import com.comicatlas.api.exporter.interfaces.rest.dto.ExportArtifactVO;
import com.comicatlas.api.exporter.interfaces.rest.dto.ExportTaskVO;
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
