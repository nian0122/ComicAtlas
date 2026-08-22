package com.comicatlas.api.outbox.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.comicatlas.api.outbox.entity.OutboxMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * Outbox 消息 Mapper。
 */
@Mapper
public interface OutboxMessageMapper extends BaseMapper<OutboxMessage> {

    /**
     * 抢占式轮询待发布消息（FOR UPDATE SKIP LOCKED）。
     * NULL available_at 视为立即可用。
     */
    @Select("""
        SELECT event_id, task_id, item_id, attempt, exchange, routing_key, event_type,
               version, payload, publish_attempts, status, available_at, published_at,
               last_error, created_at
        FROM outbox_message
        WHERE status = 'PENDING' AND (available_at IS NULL OR available_at <= NOW())
        ORDER BY created_at ASC
        LIMIT #{limit}
        FOR UPDATE SKIP LOCKED
        """)
    List<OutboxMessage> pollPending(@Param("limit") int limit);

    /**
     * 统计 PENDING 消息数。
     */
    @Select("SELECT COUNT(*) FROM outbox_message WHERE status = 'PENDING'")
    long countPending();

    /**
     * 统计 FAILED 消息数。
     */
    @Select("SELECT COUNT(*) FROM outbox_message WHERE status = 'FAILED'")
    long countFailed();

    /**
     * 删除 published_at 超过指定天数的 PUBLISHED 消息。
     */
    @Delete("DELETE FROM outbox_message WHERE status = 'PUBLISHED' AND published_at < DATE_SUB(NOW(), INTERVAL #{days} DAY)")
    int deletePublishedOlderThan(@Param("days") int days);

    /**
     * 删除 created_at 超过指定天数的 FAILED 消息。
     */
    @Delete("DELETE FROM outbox_message WHERE status = 'FAILED' AND created_at < DATE_SUB(NOW(), INTERVAL #{days} DAY)")
    int deleteFailedOlderThan(@Param("days") int days);

    /**
     * 发布失败回退：用 MySQL NOW() 计算 backoff 时间，避免 JVM/DB 时钟偏差。
     */
    @Update("UPDATE outbox_message SET publish_attempts=#{attempts}, available_at=DATE_ADD(NOW(), INTERVAL #{backoffSecs} SECOND), last_error=#{error} WHERE event_id=#{eventId}")
    int updateFailureBackoff(@Param("eventId") String eventId, @Param("attempts") int attempts,
                             @Param("backoffSecs") int backoffSecs, @Param("error") String error);

    /**
     * Confirm nack 重置：用 MySQL NOW() 计算 backoff。
     */
    @Update("UPDATE outbox_message SET status='PENDING', publish_attempts=#{attempts}, available_at=DATE_ADD(NOW(), INTERVAL #{backoffSecs} SECOND), last_error=#{error} WHERE event_id=#{eventId}")
    int resetForRetryBySql(@Param("eventId") String eventId, @Param("attempts") int attempts,
                           @Param("backoffSecs") int backoffSecs, @Param("error") String error);
}
