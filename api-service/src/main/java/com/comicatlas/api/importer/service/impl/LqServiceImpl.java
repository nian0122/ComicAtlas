package com.comicatlas.api.importer.service.impl;

import com.comicatlas.api.importer.service.LqService;
import com.comicatlas.api.management.dto.OperationSubmitResult;
import com.comicatlas.api.management.operation.MediaOperationCommandService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * LQ 生成服务 — 统一任务管线入口。
 * <p>
 * 通过 {@link MediaOperationCommandService} 创建 ManagementTask 并发布
 * ManagementCommandRequestedEvent；regenerate=true 时显式重新生成（LQ READY
 * 也进入新 attempt），默认 false 时不重置已 READY 页面。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LqServiceImpl implements LqService {

    private final MediaOperationCommandService mediaOperationCommandService;

    @Override
    public OperationSubmitResult generateForComic(Long comicId, boolean regenerate) {
        return mediaOperationCommandService.requestLqForComic(comicId, regenerate);
    }

    @Override
    public OperationSubmitResult generateForChapter(Long chapterId, boolean regenerate) {
        return mediaOperationCommandService.requestLqForChapter(chapterId, regenerate);
    }
}
