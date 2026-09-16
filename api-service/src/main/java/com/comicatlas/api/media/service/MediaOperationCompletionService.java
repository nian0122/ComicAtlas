package com.comicatlas.api.media.service;

import com.comicatlas.common.event.ManagementCommandCompletedEvent;
import com.comicatlas.common.event.payload.LqSizeResult;
import java.util.List;

/** 媒体操作结果服务契约。 */
public interface MediaOperationCompletionService {
    void applyLqCompleted(Long chapterId, List<LqSizeResult> lqSizes);
    void applyHqDeleteCompleted(Long chapterId);
    void applyTranscodeCompleted(ManagementCommandCompletedEvent event, Long mediaId);
    void maybeNotifyTranscodeTaskCompleted(ManagementCommandCompletedEvent event);
    void applyLqFailed(Long chapterId, List<LqSizeResult> lqSizes);
    void revertLqFailed(Long chapterId);
    void revertHqDeleteFailed(Long targetId);
    void revertTranscodeFailed(Long mediaId);
    void transitionLqGenerating(Long chapterId);
    void transitionHqDeleting(Long chapterId);
    void transitionTranscoding(Long mediaId);
}
