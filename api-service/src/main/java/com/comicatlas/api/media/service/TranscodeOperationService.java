package com.comicatlas.api.media.service;

import com.comicatlas.api.task.dto.OperationSubmitResultDTO;
import com.comicatlas.api.media.service.MediaOperationCommandService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 视频转码操作服务（存储操作域）。支持漫画级、章节级与媒体级。
 */
@Service
// TODO(LAYER-14): 具体 Service 实现位于 service 包，未与 Service 接口及 service/impl 实现分离。
@RequiredArgsConstructor
public class TranscodeOperationService {
    // 转码操作契约由应用服务公开，具体实现保持在媒体业务包内。

    private final MediaOperationCommandService commandService;

    public OperationSubmitResultDTO transcodeForComic(Long comicId) {
        return commandService.requestTranscodeForComic(comicId);
    }

    public OperationSubmitResultDTO transcodeForChapter(Long chapterId) {
        return commandService.requestTranscodeForChapter(chapterId);
    }

    public OperationSubmitResultDTO transcodeForMedia(Long mediaId) {
        return commandService.requestTranscodeForMedia(mediaId);
    }
}
