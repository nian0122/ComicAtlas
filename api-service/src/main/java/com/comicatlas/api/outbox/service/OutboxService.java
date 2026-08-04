package com.comicatlas.api.outbox.service;

import com.comicatlas.common.event.ComicEvent;
import lombok.NonNull;

/**
 * 事务 Outbox 服务。
 * <p>
 * 在业务的同一事务中写入 outbox 记录，relay 负责异步发布到 MQ。
 * 调用方必须在 {@code @Transactional} 方法中调用，调用后不直接操作 RabbitMQ。
 */
public interface OutboxService {

    /**
     * 写入 outbox 记录（与业务同事务）。
     *
     * @param event       事件对象
     * @param exchange    目标 exchange
     * @param routingKey  目标 routing key
     */
    void enqueue(@NonNull ComicEvent event, @NonNull String exchange, @NonNull String routingKey);

    /**
     * 写入 outbox 记录，带 task/item 引用。
     *
     * @param event       事件对象
     * @param exchange    目标 exchange
     * @param routingKey  目标 routing key
     * @param taskId      关联 management_task.id（可为 null）
     * @param itemId      关联 management_task_item.id（可为 null）
     * @param attempt     当前 attempt 号
     */
    void enqueue(@NonNull ComicEvent event, @NonNull String exchange, @NonNull String routingKey,
                 Long taskId, Long itemId, int attempt);
}
