package com.comicatlas.worker.event;

import com.comicatlas.common.constant.MqQueues;
import com.comicatlas.common.event.CancelTaskEvent;
import com.comicatlas.common.mq.MqConsumerSupport;
import com.comicatlas.worker.config.WorkerConfig;
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
 *
 * QA 修复注记（task-21 run 6）：
 * 原 WIP 在类上加了 @ConditionalOnBean(StringRedisTemplate.class)，但该条件作用于用户
 * @Component 时在组件扫描阶段求值，而 StringRedisTemplate 由 auto-configuration 在
 * 组件扫描之后才注册 bean 定义，导致条件恒不成立 → CancelHandler 缺失 →
 * DirectoryImportHandler 构造注入失败 → Worker 独立启动必然失败（APPLICATION FAILED
 * TO START）。StringRedisTemplate 随 spring-boot-starter-data-redis 必然存在，
 * 移除该条件注解即恢复 worker 独立可启动性（与 git HEAD 行为一致）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CancelHandler {

    public static final String KEY_PREFIX = "import:cancel:";
    private final WorkerConfig workerConfig;
    private final StringRedisTemplate redisTemplate;
    private final MqConsumerSupport mqConsumerSupport;

    public CancelHandler(StringRedisTemplate redisTemplate, MqConsumerSupport mqConsumerSupport) {
        this(redisTemplate, mqConsumerSupport, new WorkerConfig());
    }

    public CancelHandler(StringRedisTemplate redisTemplate, MqConsumerSupport mqConsumerSupport,
                         WorkerConfig workerConfig) {
        this.redisTemplate = redisTemplate;
        this.mqConsumerSupport = mqConsumerSupport;
        this.workerConfig = workerConfig;
    }

    @RabbitListener(queues = MqQueues.CANCEL_TASK)
    public void handle(CancelTaskEvent event, Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long tag) {
        mqConsumerSupport.consume(channel, tag, "取消标记: taskId=" + event.taskId(),
                () -> redisTemplate.opsForValue().set(KEY_PREFIX + event.taskId(), "1",
                        Duration.ofDays(workerConfig.getLifecycle().getCancellationTtlDays())),
                null, MqConsumerSupport.FailurePolicy.REQUEUE);
    }

    public boolean isCancelled(Long taskId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(KEY_PREFIX + taskId));
    }
}
