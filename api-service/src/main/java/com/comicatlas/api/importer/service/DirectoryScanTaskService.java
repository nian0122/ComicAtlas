package com.comicatlas.api.importer.service;

import com.comicatlas.api.importer.dto.DirectoryScanTaskVO;
import com.comicatlas.common.dto.ScanResultVO;

public interface DirectoryScanTaskService {
    DirectoryScanTaskVO createScanTask(String directoryPath);
    DirectoryScanTaskVO getTaskDetail(Long id);
    void applyResult(Long taskId, ScanResultVO result);
    void applyFailure(Long taskId, String errorMessage);
}
