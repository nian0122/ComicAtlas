package com.comicatlas.api.task.service;

/** 管理任务聚合服务契约。 */
public interface TaskAggregationService {
    void aggregate(Long taskId);
}
