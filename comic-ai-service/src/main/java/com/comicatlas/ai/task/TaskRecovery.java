package com.comicatlas.ai.task;

import com.comicatlas.ai.analysis.AnalysisWorker;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** 容器重启后恢复未完成任务；重复领取仍由数据库条件更新保护。 */
@Component
public class TaskRecovery {
    private final TaskRepository repository;
    private final AnalysisWorker worker;
    public TaskRecovery(TaskRepository repository, AnalysisWorker worker) { this.repository = repository; this.worker = worker; }
    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        repository.requeueInterruptedTasks();
        repository.queuedTaskIds().forEach(worker::execute);
    }
}
