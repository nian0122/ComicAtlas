package com.comicatlas.api.export.service;

import com.comicatlas.api.export.dto.ExportTaskVO;

import java.util.List;

public interface ExportService {

    ExportTaskVO createExportTask(Long comicId);

    List<ExportTaskVO> listExports(Long comicId);

    ExportTaskVO getTask(Long taskId);
}
