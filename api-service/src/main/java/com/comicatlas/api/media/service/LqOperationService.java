package com.comicatlas.api.media.service;

import com.comicatlas.api.task.dto.OperationSubmitResultDTO;

/** LQ 生成操作服务契约。 */
public interface LqOperationService {
    OperationSubmitResultDTO generateForComic(Long comicId, boolean regenerate);
    OperationSubmitResultDTO generateForChapter(Long chapterId, boolean regenerate);
}
