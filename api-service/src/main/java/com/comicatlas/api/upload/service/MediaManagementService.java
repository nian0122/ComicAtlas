package com.comicatlas.api.upload.service;

import com.comicatlas.api.task.dto.OperationSubmitResultDTO;
import com.comicatlas.api.upload.dto.MediaReorderRequest;
import com.comicatlas.api.upload.dto.MediaReorderResponse;

/** 媒体管理服务契约。 */
public interface MediaManagementService {
    MediaReorderResponse reorder(Long chapterId, MediaReorderRequest request);
    OperationSubmitResultDTO trash(Long mediaId);
}
