package com.comicatlas.api.outbox.domain.model;

/**
 * Outbox 消息生命周期状态。
 */
public enum OutboxMessageStatus {

    /** 等待 relay 发布。 */
    PENDING,

    /** 已收到 broker confirm。 */
    PUBLISHED,

    /** 达到最大重试次数，等待人工处理。 */
    FAILED
}
