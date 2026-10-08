package com.comicatlas.api.task.batch.service;

import com.comicatlas.api.task.batch.dto.BatchCreateResponse;
import com.comicatlas.api.task.batch.dto.BatchOperationRequest;
import com.comicatlas.api.task.batch.dto.BatchPreviewResponse;

/** 批量操作服务契约。 */
public interface BatchOperationService {
    BatchPreviewResponse preview(BatchOperationRequest request);
    BatchCreateResponse createBatch(BatchOperationRequest request, String idempotencyKey);
}
