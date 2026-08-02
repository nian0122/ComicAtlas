package com.comicatlas.api.importer.event;

import com.comicatlas.api.importer.service.DirectoryScanTaskService;
import com.comicatlas.common.event.ComicEvent;
import com.comicatlas.common.event.DirectoryScanCompletedEvent;
import com.comicatlas.common.event.DirectoryScanFailedEvent;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DirectoryScanEventHandler {

    private final DirectoryScanTaskService scanTaskService;

    @RabbitListener(queues = "scan.result.queue")
    public void handle(ComicEvent event,
                       Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long tag) {

        try {
            if (event instanceof DirectoryScanCompletedEvent completedEvent) {
                handleCompleted(completedEvent);
            } else if (event instanceof DirectoryScanFailedEvent failedEvent) {
                handleFailed(failedEvent);
            } else {
                log.warn("scan.result.queue 收到未知事件类型: {}, ack 跳过",
                        event.getClass().getSimpleName());
            }
            ack(channel, tag);
        } catch (Exception e) {
            log.error("目录扫描结果事件处理失败", e);
            reject(channel, tag);
        }
    }

    private void handleCompleted(DirectoryScanCompletedEvent event) {
        log.info("目录扫描完成: taskId={}, total={}", event.taskId(), event.result().total());
        scanTaskService.applyResult(event.taskId(), event.result());
    }

    private void handleFailed(DirectoryScanFailedEvent event) {
        log.warn("目录扫描失败: taskId={}, error={}", event.taskId(), event.errorMessage());
        scanTaskService.applyFailure(event.taskId(), event.errorMessage());
    }

    private void ack(Channel channel, long tag) {
        try {
            channel.basicAck(tag, false);
        } catch (Exception e) {
            log.error("消息 ack 失败: tag={}", tag, e);
        }
    }

    private void reject(Channel channel, long tag) {
        try {
            channel.basicReject(tag, false);
        } catch (Exception e) {
            log.error("消息 reject 失败: tag={}", tag, e);
        }
    }
}
