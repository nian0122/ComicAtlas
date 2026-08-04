package com.comicatlas.api.importer.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.comicatlas.api.importer.dto.BatchImportRequest;
import com.comicatlas.api.importer.dto.BatchImportResultVO;
import com.comicatlas.api.importer.dto.ImportRequest;
import com.comicatlas.api.importer.dto.ImportStatusVO;
import com.comicatlas.api.importer.dto.ImportTaskVO;

public interface ImportService {
    /** 创建导入任务：预创建 comic + management task 同事务，支持 Idempotency-Key */
    ImportTaskVO createImportTask(ImportRequest request, String idempotencyKey);
    IPage<ImportTaskVO> listTasks(Integer page, Integer size, String status, String batchId);
    ImportTaskVO getTaskDetail(Long id);
    ImportStatusVO getTaskStatus(Long id);
    void cancelTask(Long id);
    void retryTask(Long id);
    BatchImportResultVO createBatchImportTasks(BatchImportRequest request);
}
