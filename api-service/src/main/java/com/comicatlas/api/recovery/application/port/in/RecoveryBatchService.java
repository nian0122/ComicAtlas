package com.comicatlas.api.recovery.application.port.in;

import com.comicatlas.common.event.RecoveryFailedEvent;
import com.comicatlas.common.event.RecoveryScanCompletedEvent;

/** 恢复批次业务编排服务契约。 */
public interface RecoveryBatchService {
    void processScanCompleted(RecoveryScanCompletedEvent event);
    void processFailed(RecoveryFailedEvent event);
    void markProcessingFailure(Long taskId, Exception exception);
}
