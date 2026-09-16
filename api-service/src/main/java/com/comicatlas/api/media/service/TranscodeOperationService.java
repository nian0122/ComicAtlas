package com.comicatlas.api.media.service;

import com.comicatlas.api.task.dto.OperationSubmitResultDTO;

/** 视频转码操作服务契约。 */
public interface TranscodeOperationService {
    OperationSubmitResultDTO transcodeForComic(Long comicId);
    OperationSubmitResultDTO transcodeForChapter(Long chapterId);
    OperationSubmitResultDTO transcodeForMedia(Long mediaId);
}
