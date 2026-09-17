package com.comicatlas.api.metadata.application.port.in;

import com.comicatlas.common.event.MetadataRefreshScanCompletedEvent;

/** 元数据刷新完成处理服务契约。 */
public interface MetadataRefreshCompletionService {
    void handleCompleted(MetadataRefreshScanCompletedEvent event);
    void handleCommandFailed(Long taskId, Long itemId, int attempt, String targetType, Long targetId);
}
