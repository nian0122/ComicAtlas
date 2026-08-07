package com.comicatlas.api.export.event;

import com.comicatlas.api.common.enums.ExportTaskStatus;
import com.comicatlas.api.export.entity.ExportTask;
import com.comicatlas.api.export.mapper.ExportTaskMapper;
import com.comicatlas.api.management.entity.ManagementTaskItem;
import com.comicatlas.api.management.service.ManagementTaskService;
import com.comicatlas.common.constant.MqQueues;
import com.comicatlas.common.enums.ManagementTaskStatus;
import com.comicatlas.common.enums.TaskType;
import com.comicatlas.common.event.ExportTaskCompletedEvent;
import com.comicatlas.common.mq.MqConsumerSupport;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 导出完成事件处理器。
 * 接收 Worker 发来的 task.completed 事件，更新 ExportTask 为 SUCCESS。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExportCompletedHandler {

    private final ExportTaskMapper exportTaskMapper;
    private final ManagementTaskService managementTaskService;
    private final MqConsumerSupport mqConsumerSupport;

    @RabbitListener(queues = MqQueues.EXPORT_COMPLETED_RESULT)
    public void handle(ExportTaskCompletedEvent event,
            Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long tag) {
        Long taskId = event.taskId();
        Long comicId = event.comicId();
        log.info("导出完成事件: taskId={}, comicId={}, outputSize={}",
                taskId, comicId, event.outputSize());

        mqConsumerSupport.consume(channel, tag, "导出完成: taskId=" + taskId, () -> {
            ExportTask task = exportTaskMapper.selectById(taskId);
            if (task != null) {
                task.setStatus(ExportTaskStatus.SUCCESS);
                task.setOutputRoot(event.outputRoot());
                task.setOutputPath(event.outputPath());
                task.setOutputSize(event.outputSize());
                task.setProgress(100);
                task.setCompletedAt(LocalDateTime.now());
                exportTaskMapper.updateById(task);
            }

            // 同步统一任务项为 SUCCEEDED
            ManagementTaskItem mgmtItem = managementTaskService.findActiveItem("COMIC", comicId, TaskType.EXPORT);
            if (mgmtItem != null) {
                managementTaskService.updateItemStatus(mgmtItem.getId(), ManagementTaskStatus.SUCCEEDED,
                        null, "EXPORT_TASK", taskId);
            }

            log.info("导出状态更新为 SUCCESS: taskId={}", taskId);
        });
    }
}
