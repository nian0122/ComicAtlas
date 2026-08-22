package com.comicatlas.worker.task;

/** 任务被用户取消时使用的控制流异常，不能被当作普通失败处理。 */
public class TaskCancelledException extends RuntimeException {

    public TaskCancelledException(Long taskId) {
        super("任务已取消: taskId=" + taskId);
    }
}
