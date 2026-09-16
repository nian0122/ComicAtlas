package com.comicatlas.api.media.service;

import com.comicatlas.common.event.VideoMetadataFixCompletedEvent;

/** 视频元数据修复完成服务契约。 */
public interface VideoMetadataFixService {
    void apply(VideoMetadataFixCompletedEvent event);
}
