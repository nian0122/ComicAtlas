package com.comicatlas.api.task.application.port.in.batch;

import com.comicatlas.api.task.interfaces.rest.dto.batch.BatchCreateResponse;
import com.comicatlas.api.task.interfaces.rest.dto.batch.BatchOperationRequest;
import com.comicatlas.api.task.interfaces.rest.dto.batch.BatchPreviewResponse;

/** 批量操作服务契约。 */
public interface BatchOperationService {
    BatchPreviewResponse preview(BatchOperationRequest request);
    BatchCreateResponse createBatch(BatchOperationRequest request, String idempotencyKey);
}
