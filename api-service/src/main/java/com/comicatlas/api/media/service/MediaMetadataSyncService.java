package com.comicatlas.api.media.service;

/** 媒体元数据同步通知服务契约。 */
public interface MediaMetadataSyncService {
    void notifyTranscoded(Long mediaId, Long taskId);
    void notifyTaskTranscoded(Long comicId, Long taskId);
}
