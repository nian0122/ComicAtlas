package com.comicatlas.api.exporter.application.service.impl;

import com.comicatlas.api.exporter.interfaces.rest.dto.ExportTaskVO;
import com.comicatlas.api.exporter.application.port.in.ExportService;
import com.comicatlas.api.exporter.interfaces.rest.dto.ExportArtifactVO;
import com.comicatlas.api.exporter.application.port.in.ExportArtifactService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 导出操作服务（存储操作域）。委托 ExportService 与 ExportArtifactService，端点归位到 /api/storage/export。
 */
@Service
@RequiredArgsConstructor
public class ExportOperationServiceImpl implements com.comicatlas.api.exporter.application.port.in.ExportOperationService {
    // 导出操作契约由应用服务公开，具体实现保持在导出业务包内。

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
