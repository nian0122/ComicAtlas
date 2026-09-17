package com.comicatlas.api.importer.application.port.in;

import com.comicatlas.api.shared.application.model.PageResult;
import com.comicatlas.api.importer.interfaces.rest.dto.BatchImportRequest;
import com.comicatlas.api.importer.interfaces.rest.dto.BatchImportResultVO;
import com.comicatlas.api.importer.interfaces.rest.dto.ImportRequest;
import com.comicatlas.api.importer.interfaces.rest.dto.ImportStatusVO;
import com.comicatlas.api.importer.interfaces.rest.dto.ImportTaskVO;

public interface ImportService {
    /** 创建导入任务：预创建 comic + management task 同事务，支持 Idempotency-Key */
    ImportTaskVO createImportTask(ImportRequest request, String idempotencyKey);
    PageResult<ImportTaskVO> listTasks(Integer page, Integer size, String status, String batchId);
    ImportTaskVO getTaskDetail(Long id);
    ImportStatusVO getTaskStatus(Long id);
    void cancelTask(Long id);
    void retryTask(Long id);
    BatchImportResultVO createBatchImportTasks(BatchImportRequest request);
}
