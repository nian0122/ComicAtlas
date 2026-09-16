package com.comicatlas.api.media.service;

import com.comicatlas.api.task.dto.OperationSubmitResultDTO;
import com.comicatlas.api.media.service.MediaOperationCommandService;
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
    // 媒体操作契约由应用服务公开，具体实现保持在媒体业务包内。

    private final MediaOperationCommandService commandService;

    public OperationSubmitResultDTO generateForComic(Long comicId, boolean regenerate) {
        return commandService.requestLqForComic(comicId, regenerate);
    }

    public OperationSubmitResultDTO generateForChapter(Long chapterId, boolean regenerate) {
        return commandService.requestLqForChapter(chapterId, regenerate);
    }
}
