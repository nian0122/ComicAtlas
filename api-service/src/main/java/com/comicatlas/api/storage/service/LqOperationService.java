package com.comicatlas.api.storage.service;

import com.comicatlas.api.management.dto.OperationSubmitResult;
import com.comicatlas.api.management.operation.MediaOperationCommandService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * LQ 生成操作服务（存储操作域）。
 * <p>
 * 统一委托 MediaOperationCommandService 走 ManagementTask 任务管线。
 * 与 importer 包解耦：本类不依赖任何 importer 类型。
 */
@Service
@RequiredArgsConstructor
public class LqOperationService {

    private final MediaOperationCommandService commandService;

    public OperationSubmitResult generateForComic(Long comicId, boolean regenerate) {
        return commandService.requestLqForComic(comicId, regenerate);
    }

    public OperationSubmitResult generateForChapter(Long chapterId, boolean regenerate) {
        return commandService.requestLqForChapter(chapterId, regenerate);
    }
}
