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
    // TODO(LAYER-14): Service 功能契约与具体实现未分离；应抽取 service 接口，并将实现迁移到 service/impl。

    private final MediaOperationCommandService commandService;

    public OperationSubmitResultDTO generateForComic(Long comicId, boolean regenerate) {
        return commandService.requestLqForComic(comicId, regenerate);
    }

    public OperationSubmitResultDTO generateForChapter(Long chapterId, boolean regenerate) {
        return commandService.requestLqForChapter(chapterId, regenerate);
    }
}
