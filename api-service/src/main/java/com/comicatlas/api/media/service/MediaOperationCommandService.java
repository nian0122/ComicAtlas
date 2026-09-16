package com.comicatlas.api.media.service;

import com.comicatlas.api.task.dto.OperationSubmitResultDTO;

/** 媒体操作命令服务契约。 */
public interface MediaOperationCommandService {
    OperationSubmitResultDTO requestLqForComic(Long comicId, boolean regenerate);
    OperationSubmitResultDTO requestLqForChapter(Long chapterId, boolean regenerate);
    OperationSubmitResultDTO requestHqDeleteForComic(Long comicId);
    OperationSubmitResultDTO requestHqDeleteForChapter(Long chapterId);
    OperationSubmitResultDTO requestTranscodeForComic(Long comicId);
    OperationSubmitResultDTO requestTranscodeForChapter(Long chapterId);
    OperationSubmitResultDTO requestTranscodeForMedia(Long mediaId);
    OperationSubmitResultDTO requestMetadataRefresh(Long comicId);
    OperationSubmitResultDTO requestComicDelete(Long comicId);
}
