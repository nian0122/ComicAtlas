package com.comicatlas.worker.task;

/**
 * 任务状态更新参数对象：承载 {@link TaskStatusChangedEvent} 的业务载荷字段，
 * 作为 {@link TaskStatusPublisher#publishStatus} 的入参，避免 7 个散落参数
 * （阿里规范：方法参数尽量不超过 5 个）。
 * <p>
 * eventId/occurredAt 由发布器内部生成，不在此承载。
 */
public record TaskStatusUpdate(
        Long taskId,
        String status,
        int progress,
        String downloadMethod,
        long speedBytesPerSec,
        int etaSeconds,
        String errorMessage
) {
}
