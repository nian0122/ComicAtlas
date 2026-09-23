package com.comicatlas.worker.task.model;

/**
 * 任务状态更新参数对象：承载 {@link TaskStatusChangedEvent} 的业务载荷字段，
 * 作为 {@link TaskStatusPublisher#publishStatus} 的入参，避免 7 个散落参数
 * （阿里规范：方法参数尽量不超过 5 个）。
 * <p>
 * eventId/occurredAt 由发布器内部生成，不在此承载。
 */
public final class TaskStatusUpdate {
    private final Long taskId;
    private final String status;
    private final int progress;
    private final String downloadMethod;
    private final long speedBytesPerSec;
    private final int etaSeconds;
    private final String errorMessage;

    public TaskStatusUpdate(Long taskId, String status, int progress, String downloadMethod,
                            long speedBytesPerSec, int etaSeconds, String errorMessage) {
        this.taskId = taskId;
        this.status = status;
        this.progress = progress;
        this.downloadMethod = downloadMethod;
        this.speedBytesPerSec = speedBytesPerSec;
        this.etaSeconds = etaSeconds;
        this.errorMessage = errorMessage;
    }

    public Long taskId() { return taskId; }
    public String status() { return status; }
    public int progress() { return progress; }
    public String downloadMethod() { return downloadMethod; }
    public long speedBytesPerSec() { return speedBytesPerSec; }
    public int etaSeconds() { return etaSeconds; }
    public String errorMessage() { return errorMessage; }
}
