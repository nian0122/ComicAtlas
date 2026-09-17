package com.comicatlas.api.upload.application.port.in;

import com.comicatlas.api.task.interfaces.rest.dto.OperationSubmitResultDTO;
import com.comicatlas.api.upload.interfaces.rest.dto.MediaReorderRequest;
import com.comicatlas.api.upload.interfaces.rest.dto.MediaReorderResponse;

/** 媒体管理服务契约。 */
public interface MediaManagementService {
    MediaReorderResponse reorder(Long chapterId, MediaReorderRequest request);
    OperationSubmitResultDTO trash(Long mediaId);
}
