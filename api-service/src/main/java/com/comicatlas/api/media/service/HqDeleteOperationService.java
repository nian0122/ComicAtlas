package com.comicatlas.api.media.service;

import com.comicatlas.api.task.dto.OperationSubmitResultDTO;

/** HQ 删除操作服务契约。 */
public interface HqDeleteOperationService {
    OperationSubmitResultDTO deleteForComic(Long comicId);
    OperationSubmitResultDTO deleteForChapter(Long chapterId);
}
