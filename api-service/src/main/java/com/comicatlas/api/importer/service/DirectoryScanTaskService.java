package com.comicatlas.api.importer.service;

import com.comicatlas.api.importer.dto.DirectoryScanTaskVO;
import com.comicatlas.common.dto.ScanResultDTO;

public interface DirectoryScanTaskService {
    DirectoryScanTaskVO createScanTask(String directoryPath);
    DirectoryScanTaskVO getTaskDetail(Long id);
    DirectoryScanTaskVO retryTask(Long id);
    void applyResult(Long taskId, ScanResultDTO result);
    void applyFailure(Long taskId, String errorMessage);
}
