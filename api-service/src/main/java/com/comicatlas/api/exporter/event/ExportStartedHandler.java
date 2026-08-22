package com.comicatlas.api.exporter.event;

import com.comicatlas.api.exporter.enums.ExportTaskStatus;
import com.comicatlas.api.exporter.entity.ExportTask;
import com.comicatlas.api.exporter.mapper.ExportTaskMapper;
import com.comicatlas.api.task.entity.ManagementTaskItem;
import com.comicatlas.api.task.service.ManagementTaskService;
import com.comicatlas.common.constant.MqQueues;
import com.comicatlas.api.task.enums.ManagementTaskStatus;
import com.comicatlas.api.task.enums.TaskType;
import com.comicatlas.common.event.ExportTaskStartedEvent;
import com.comicatlas.common.mq.MqConsumerSupport;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 导出启动事件处理器。
 * 接收 Worker 发来的 task.started 事件，更新 ExportTask 状态为 RUNNING。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExportStartedHandler {

    private final ExportTaskMapper exportTaskMapper;
    private final ManagementTaskService managementTaskService;
    private final MqConsumerSupport mqConsumerSupport;
    private final TransactionTemplate transactionTemplate;

    @RabbitListener(queues = MqQueues.EXPORT_STARTED_RESULT)
    public void handle(ExportTaskStartedEvent event,
            Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long tag) {
        Long taskId = event.taskId();
        Long comicId = event.comicId();
        log.info("导出启动事件: taskId={}, comicId={}", taskId, comicId);

        mqConsumerSupport.consume(channel, tag, "导出启动: taskId=" + taskId,
                () -> transactionTemplate.executeWithoutResult(tx -> {
            ExportTask task = exportTaskMapper.selectById(taskId);
            if (task != null && task.getStatus() == ExportTaskStatus.PENDING) {
                task.setStatus(ExportTaskStatus.RUNNING);
                exportTaskMapper.updateById(task);
            } else if (task == null || task.getStatus() != ExportTaskStatus.RUNNING) {
                // 旧事件或终态事件不得回退/重开任务。
                log.info("忽略导出启动事件: taskId={}, status={}", taskId,
                        task == null ? null : task.getStatus());
                return;
            }

            // 同步统一任务项为 RUNNING
            ManagementTaskItem mgmtItem = managementTaskService.findActiveItem("COMIC", comicId, TaskType.EXPORT);
            if (mgmtItem != null) {
                managementTaskService.updateItemStatus(mgmtItem.getId(), ManagementTaskStatus.RUNNING,
                        null, "EXPORT_TASK", taskId);
            }

            log.info("导出状态更新为 RUNNING: taskId={}", taskId);
                }));
    }
}
