package com.comicatlas.api.task.application.port.in;

/** 历史任务回填服务契约。 */
public interface LegacyTaskBackfillService {
    int backfillAll();
}
