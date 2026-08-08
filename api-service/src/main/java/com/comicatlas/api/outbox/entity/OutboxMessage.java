package com.comicatlas.api.outbox.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 事务 Outbox 消息实体。
 * <p>
 * 业务写入 + outbox 记录同事务，relay 异步轮询发布到 MQ。
 * <p>数据库实体（DO），禁止直接暴露给接口；对外使用 {@code dto/} 包对应 DTO/VO。
 */
@Data
@Accessors(chain = true)
@TableName("outbox_message")
public class OutboxMessage {

    /** 事件 UUID */
    @TableId(type = IdType.INPUT)
    private String eventId;

    /** 关联 management_task.id（可选） */
    private Long taskId;

    /** 关联 management_task_item.id（可选） */
    private Long itemId;

    /** task/item attempt 快照 */
    private Integer attempt;

    /** 目标 exchange */
    private String exchange;

    /** 目标 routing key */
    private String routingKey;

    /** ComicEvent.eventType */
    private String eventType;

    /** ComicEvent.version() */
    private Integer version;

    /** JSON 序列化的事件体 */
    private String payload;

    /** relay 发布尝试次数 */
    private Integer publishAttempts;

    /** 状态: PENDING/PUBLISHED/FAILED */
    private String status;

    /** 最早可发布时间（用于指数退避） */
    private LocalDateTime availableAt;

    /** 确认发布时间 */
    private LocalDateTime publishedAt;

    /** 最后一次发布错误 */
    private String lastError;

    /** 创建时间 */
    private LocalDateTime createdAt;
}
