package com.comicatlas.api.media.service.impl;

import com.comicatlas.api.task.dto.OperationSubmitResultDTO;
import com.comicatlas.api.media.service.MediaOperationCommandService;
import com.comicatlas.api.media.service.HqDeleteOperationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * HQ 删除操作服务（存储操作域）。删除 HQ 保留 LQ。
 * 统一委托 MediaOperationCommandService 走 ManagementTask 任务管线。
 */
@Service
@RequiredArgsConstructor
public class HqDeleteOperationServiceImpl implements HqDeleteOperationService {
    // 媒体操作契约由应用服务公开，具体实现保持在媒体业务包内。

    private final MediaOperationCommandService commandService;

    public OperationSubmitResultDTO deleteForComic(Long comicId) {
        return commandService.requestHqDeleteForComic(comicId);
    }

    public OperationSubmitResultDTO deleteForChapter(Long chapterId) {
        return commandService.requestHqDeleteForChapter(chapterId);
    }
}
