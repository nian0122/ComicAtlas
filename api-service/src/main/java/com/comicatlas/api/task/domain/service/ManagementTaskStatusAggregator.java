package com.comicatlas.api.task.domain.service;

import com.comicatlas.api.task.domain.model.ManagementTaskStatus;

/**
 * 管理任务聚合状态规则。
 *
 * <p>该服务只接收领域状态与计数，不感知数据库、事务和消息基础设施；时间戳等副作用由应用层负责。</p>
 */
public final class ManagementTaskStatusAggregator {

    private ManagementTaskStatusAggregator() {
    }

    /**
     * 根据任务项快照计算主任务状态。
     *
     * @param currentStatus 当前主任务状态
     * @param totalCount 任务项总数
     * @param successCount 成功项数量
     * @param failureCount 失败项数量
     * @param cancelledCount 取消项数量
     * @param hasRunning 是否仍有运行中或取消中的项
     * @param hasQueued 是否仍有排队项
     * @return 聚合后的主任务状态；若仍有活动项且当前任务未进入运行态，则返回当前状态
     */
    public static ManagementTaskStatus aggregate(ManagementTaskStatus currentStatus,
                                                   long totalCount,
                                                   long successCount,
                                                   long failureCount,
                                                   long cancelledCount,
                                                   boolean hasRunning,
                                                   boolean hasQueued) {
        if (currentStatus == ManagementTaskStatus.CANCELLING && !hasRunning && !hasQueued) {
            return ManagementTaskStatus.CANCELLED;
        }
        if (!hasRunning && !hasQueued) {
            return terminalStatus(totalCount, successCount, failureCount, cancelledCount);
        }
        if ((hasRunning || currentStatus == ManagementTaskStatus.RUNNING)
                && currentStatus == ManagementTaskStatus.QUEUED) {
            return ManagementTaskStatus.RUNNING;
        }
        return currentStatus;
    }

    private static ManagementTaskStatus terminalStatus(long totalCount,
                                                         long successCount,
                                                         long failureCount,
                                                         long cancelledCount) {
        if (successCount == totalCount) {
            return ManagementTaskStatus.SUCCEEDED;
        }
        if (failureCount == totalCount) {
            return ManagementTaskStatus.FAILED;
        }
        if (cancelledCount == totalCount) {
            return ManagementTaskStatus.CANCELLED;
        }
        return ManagementTaskStatus.PARTIALLY_SUCCEEDED;
    }
}
