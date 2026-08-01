package com.comicatlas.worker.event;

import com.comicatlas.common.event.CancelTaskEvent;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 取消标记：以 Redis 为唯一事实来源。
 * API cancelTask 写 key、retryTask 删 key；Worker handle() 消费取消 MQ 后幂等写 key，isCancelled() 只读，永不清除取消意图。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CancelHandler {

    public static final String KEY_PREFIX = "import:cancel:";
    private static final Duration TTL = Duration.ofDays(7);

    private final StringRedisTemplate redisTemplate;

    @RabbitListener(queues = "cancel.task.queue")
    public void handle(CancelTaskEvent event,
            Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long tag) {
        redisTemplate.opsForValue().set(KEY_PREFIX + event.taskId(), "1", TTL);
        log.info("Cancel registered: taskId={}", event.taskId());
        try {
            channel.basicAck(tag, false);
        } catch (Exception e) {
            log.error("Cancel ack failed: taskId={}", event.taskId(), e);
        }
    }

    public boolean isCancelled(Long taskId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(KEY_PREFIX + taskId));
    }
}
