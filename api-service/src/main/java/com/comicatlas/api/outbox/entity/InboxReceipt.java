package com.comicatlas.api.outbox.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 结果 Inbox 收据实体。
 * <p>
 * eventId 为 PK，payload_hash 校验，保证处理恰好一次。
 */
@Data
@Accessors(chain = true)
@TableName("inbox_receipt")
public class InboxReceipt {

    /** 事件 UUID（PK） */
    @TableId(type = IdType.INPUT)
    private String eventId;

    /** payload SHA-256 */
    private String payloadHash;

    /** 关联 management_task.id（可选） */
    private Long taskId;

    /** 关联 management_task_item.id（可选） */
    private Long itemId;

    /** task/item attempt 快照 */
    private Integer attempt;

    /** 处理时间 */
    private LocalDateTime processedAt;

    private LocalDateTime createdAt;
}
