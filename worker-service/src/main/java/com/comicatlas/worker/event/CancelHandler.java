package com.comicatlas.worker.event;

import com.comicatlas.common.event.CancelTaskEvent;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class CancelHandler {

    private final ConcurrentHashMap<Long, Instant> cancelled = new ConcurrentHashMap<>();

    @RabbitListener(queues = "cancel.task.queue")
    public void handle(CancelTaskEvent event,
            Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long tag) {
        cancelled.put(event.taskId(), Instant.now());
        log.info("Cancel registered: taskId={}", event.taskId());
        try {
            channel.basicAck(tag, false);
        } catch (Exception e) {
            log.error("Cancel ack failed: taskId={}", event.taskId(), e);
        }
    }

    public boolean isCancelled(Long taskId) {
        Instant at = cancelled.get(taskId);
        if (at == null) return false;
        if (Instant.now().isAfter(at.plusSeconds(1800))) {
            cancelled.remove(taskId);
            return false;
        }
        return true;
    }
}
