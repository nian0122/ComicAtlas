package com.comicatlas.api.export.event;

import com.comicatlas.api.export.entity.ExportTask;
import com.comicatlas.api.export.mapper.ExportTaskMapper;
import com.comicatlas.common.event.ExportTaskStartedEvent;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * 导出启动事件处理器。
 * 接收 Worker 发来的 task.started 事件，更新 ExportTask 状态为 RUNNING。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExportStartedHandler {

    private final ExportTaskMapper exportTaskMapper;

    @RabbitListener(queues = "export.started.result.queue")
    public void handle(ExportTaskStartedEvent event,
            Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long tag) {
        Long taskId = event.taskId();
        Long comicId = event.comicId();
        log.info("导出启动事件: taskId={}, comicId={}", taskId, comicId);

        try {
            ExportTask task = exportTaskMapper.selectById(taskId);
            if (task != null) {
                task.setStatus("RUNNING");
                exportTaskMapper.updateById(task);
            }

            channel.basicAck(tag, false);
            log.info("导出状态更新为 RUNNING: taskId={}", taskId);
        } catch (Exception e) {
            log.error("导出启动状态更新失败: taskId={}, comicId={}", taskId, comicId, e);
            try {
                channel.basicReject(tag, false);
            } catch (Exception ignored) {
            }
        }
    }
}
