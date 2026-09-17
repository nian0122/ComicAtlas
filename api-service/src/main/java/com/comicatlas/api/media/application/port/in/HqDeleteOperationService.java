package com.comicatlas.api.media.application.port.in;

import com.comicatlas.api.task.interfaces.rest.dto.OperationSubmitResultDTO;

/** HQ 删除操作服务契约。 */
public interface HqDeleteOperationService {
    OperationSubmitResultDTO deleteForComic(Long comicId);
    OperationSubmitResultDTO deleteForChapter(Long chapterId);
}
