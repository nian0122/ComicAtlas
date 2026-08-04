package com.comicatlas.api.export.event;

import com.comicatlas.api.export.entity.ExportTask;
import com.comicatlas.api.export.mapper.ExportTaskMapper;
import com.comicatlas.api.management.entity.ManagementTaskItem;
import com.comicatlas.api.management.service.ManagementTaskService;
import com.comicatlas.common.enums.ManagementTaskStatus;
import com.comicatlas.common.enums.TaskType;
import com.comicatlas.common.event.ExportTaskFailedEvent;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * 导出失败事件处理器。
 * 接收 Worker 发来的 task.failed 事件，更新 ExportTask 为 FAILED。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExportFailedHandler {

    private final ExportTaskMapper exportTaskMapper;
    private final ManagementTaskService managementTaskService;

    @RabbitListener(queues = "export.failed.result.queue")
    public void handle(ExportTaskFailedEvent event,
            Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long tag) {
        Long taskId = event.taskId();
        Long comicId = event.comicId();
        log.info("导出失败事件: taskId={}, comicId={}, errorCode={}, errorMessage={}",
                taskId, comicId, event.errorCode(), event.errorMessage());

        try {
            ExportTask task = exportTaskMapper.selectById(taskId);
            if (task != null) {
                task.setStatus("FAILED");
                task.setErrorMsg(event.errorMessage());
                task.setProgress(-1);
                exportTaskMapper.updateById(task);
            }

            // 同步统一任务项为 FAILED
            ManagementTaskItem mgmtItem = managementTaskService.findActiveItem("COMIC", comicId, TaskType.EXPORT);
            if (mgmtItem != null) {
                managementTaskService.updateItemStatus(mgmtItem.getId(), ManagementTaskStatus.FAILED,
                        event.errorMessage(), "EXPORT_TASK", taskId);
            }

            channel.basicAck(tag, false);
            log.info("导出状态更新为 FAILED: taskId={}", taskId);
        } catch (Exception e) {
            log.error("导出失败状态更新失败: taskId={}, comicId={}", taskId, comicId, e);
            try {
                channel.basicReject(tag, false);
            } catch (Exception ignored) {
            }
        }
    }
}
