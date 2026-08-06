package com.comicatlas.api.storage.service;

import com.comicatlas.api.management.dto.OperationSubmitResult;
import com.comicatlas.api.management.operation.MediaOperationCommandService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 视频转码操作服务（存储操作域）。支持漫画级与章节级。
 */
@Service
@RequiredArgsConstructor
public class TranscodeOperationService {

    private final MediaOperationCommandService commandService;

    public OperationSubmitResult transcodeForComic(Long comicId) {
        return commandService.requestTranscodeForComic(comicId);
    }

    public OperationSubmitResult transcodeForChapter(Long chapterId) {
        return commandService.requestTranscodeForChapter(chapterId);
    }
}
