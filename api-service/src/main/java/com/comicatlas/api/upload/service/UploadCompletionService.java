package com.comicatlas.api.upload.service;

import com.comicatlas.common.event.MediaUploadCompletedEvent;

/** 上传完成结果服务契约。 */
public interface UploadCompletionService {
    void applyUploadCompletedBusiness(MediaUploadCompletedEvent event);
    void revertUploadFailed(Long targetId);
}
