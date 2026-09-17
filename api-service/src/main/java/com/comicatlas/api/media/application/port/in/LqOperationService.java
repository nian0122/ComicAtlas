package com.comicatlas.api.media.application.port.in;

import com.comicatlas.api.task.interfaces.rest.dto.OperationSubmitResultDTO;

/** LQ 生成操作服务契约。 */
public interface LqOperationService {
    OperationSubmitResultDTO generateForComic(Long comicId, boolean regenerate);
    OperationSubmitResultDTO generateForChapter(Long chapterId, boolean regenerate);
}
