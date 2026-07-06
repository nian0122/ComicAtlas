package com.comicatlas.worker.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class RabbitConfirmConfig {

    private final RabbitTemplate rabbitTemplate;

    @PostConstruct
    public void init() {
        rabbitTemplate.setConfirmCallback((CorrelationData correlationData, boolean ack, String cause) -> {
            if (ack) {
                log.debug("消息发送确认: {}", correlationData != null ? correlationData.getId() : null);
            } else {
                log.warn("消息发送失败: id={}, cause={}", correlationData != null ? correlationData.getId() : null, cause);
            }
        });

        rabbitTemplate.setReturnsCallback(returned -> {
            log.error("消息路由失败: exchange={}, routingKey={}, replyCode={}, replyText={}",
                returned.getExchange(), returned.getRoutingKey(),
                returned.getReplyCode(), returned.getReplyText());
        });
    }
}
