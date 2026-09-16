package com.comicatlas.api.exporter.service;

import com.comicatlas.api.exporter.dto.ExportTaskVO;
import com.comicatlas.api.exporter.service.ExportService;
import com.comicatlas.api.exporter.dto.ExportArtifactVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 导出操作服务（存储操作域）。委托 ExportService 与 ExportArtifactService，端点归位到 /api/storage/export。
 */
@Service
@RequiredArgsConstructor
public class ExportOperationService {
    // TODO(LAYER-14): Service 功能契约与具体实现未分离；应抽取 service 接口，并将实现迁移到 service/impl。

    private final ExportService exportService;
    private final ExportArtifactService exportArtifactService;

    public ExportTaskVO createExportTask(Long comicId) {
        return exportService.createExportTask(comicId);
    }

    public ExportTaskVO createExportTask(Long comicId, String format) {
        return exportService.createExportTask(comicId, format);
    }

    public List<ExportTaskVO> listExports(Long comicId) {
        return exportService.listExports(comicId);
    }

    public List<ExportTaskVO> listAllExports() {
        return exportService.listAllExports();
    }

    public ExportTaskVO getTask(Long taskId) {
        return exportService.getTask(taskId);
    }

    public List<ExportArtifactVO> listArtifacts(Long taskId) {
        return exportArtifactService.listArtifacts(taskId);
    }
}
