package com.comicatlas.api.media.application.port.in;

import com.comicatlas.api.task.interfaces.rest.dto.OperationSubmitResultDTO;

/** 视频转码操作服务契约。 */
public interface TranscodeOperationService {
    OperationSubmitResultDTO transcodeForComic(Long comicId);
    OperationSubmitResultDTO transcodeForChapter(Long chapterId);
    OperationSubmitResultDTO transcodeForMedia(Long mediaId);
}
