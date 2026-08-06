package com.comicatlas.api.storage.service;

import com.comicatlas.api.export.dto.ExportTaskVO;
import com.comicatlas.api.export.service.ExportService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 导出操作服务（存储操作域）。委托 ExportService，端点归位到 /api/storage/export。
 */
@Service
@RequiredArgsConstructor
public class ExportOperationService {

    private final ExportService exportService;

    public ExportTaskVO createExportTask(Long comicId) {
        return exportService.createExportTask(comicId);
    }

    public List<ExportTaskVO> listExports(Long comicId) {
        return exportService.listExports(comicId);
    }

    public ExportTaskVO getTask(Long taskId) {
        return exportService.getTask(taskId);
    }
}
