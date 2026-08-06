package com.comicatlas.api.storage.service;

import com.comicatlas.api.management.dto.OperationSubmitResult;
import com.comicatlas.api.management.operation.MediaOperationCommandService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * HQ 删除操作服务（存储操作域）。删除 HQ 保留 LQ。
 * 统一委托 MediaOperationCommandService 走 ManagementTask 任务管线。
 */
@Service
@RequiredArgsConstructor
public class HqDeleteOperationService {

    private final MediaOperationCommandService commandService;

    public OperationSubmitResult deleteForComic(Long comicId) {
        return commandService.requestHqDeleteForComic(comicId);
    }

    public OperationSubmitResult deleteForChapter(Long chapterId) {
        return commandService.requestHqDeleteForChapter(chapterId);
    }
}
