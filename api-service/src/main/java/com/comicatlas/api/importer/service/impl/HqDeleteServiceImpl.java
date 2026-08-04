package com.comicatlas.api.importer.service.impl;

import com.comicatlas.api.importer.service.HqDeleteService;
import com.comicatlas.api.management.dto.OperationSubmitResult;
import com.comicatlas.api.management.operation.MediaOperationCommandService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * HQ 删除服务 — 统一任务管线入口。
 * <p>
 * 通过 {@link MediaOperationCommandService} 创建 ManagementTask 并发布命令；
 * 前置条件（全部图片 LQ READY）不满足时抛出 409。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HqDeleteServiceImpl implements HqDeleteService {

    private final MediaOperationCommandService mediaOperationCommandService;

    @Override
    public OperationSubmitResult deleteForComic(Long comicId) {
        return mediaOperationCommandService.requestHqDeleteForComic(comicId);
    }

    @Override
    public OperationSubmitResult deleteForChapter(Long chapterId) {
        return mediaOperationCommandService.requestHqDeleteForChapter(chapterId);
    }
}
