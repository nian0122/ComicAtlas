package com.comicatlas.api.exporter.application.port.in;

import com.comicatlas.common.event.ExportTaskCompletedEvent;
import com.comicatlas.common.event.ExportTaskFailedEvent;
import com.comicatlas.common.event.ExportTaskStartedEvent;

/** 导出任务结果应用服务契约。 */
public interface ExportResultService {
    void applyStarted(ExportTaskStartedEvent event);
    void applyCompleted(ExportTaskCompletedEvent event);
    void applyFailed(ExportTaskFailedEvent event);
}
