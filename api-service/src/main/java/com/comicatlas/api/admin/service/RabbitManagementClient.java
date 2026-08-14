package com.comicatlas.api.admin.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;

/**
 * RabbitMQ Management HTTP API 客户端。
 * <p>
 * 枚举 Broker 上全部队列（含代码已不再声明、残留于 Broker 的僵尸队列），
 * 供积压/死信统计使用。Management API 不可用时由调用方降级。
 */
@Component
@RequiredArgsConstructor
public class RabbitManagementClient {

    /** 队列快照：仅请求统计所需列，避免拉取完整大响应。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record QueueSnapshot(
        String name,
        long messages,
        @JsonProperty("messages_ready") long messagesReady,
        int consumers
    ) {
    }

    private final RestTemplate rabbitManagementRestTemplate;

    @Value("${mq.management.host:${spring.rabbitmq.host:localhost}}")
    private String host;

    @Value("${mq.management.port}")
    private int port;

    /**
     * 枚举全部队列的积压快照（全 vhost）。
     *
     * @return 队列快照列表；Management API 不可用或响应异常时抛出
     */
    public List<QueueSnapshot> listQueues() {
        String url = "http://" + host + ":" + port + "/api/queues?columns=name,messages,messages_ready,consumers";
        QueueSnapshot[] snapshots = rabbitManagementRestTemplate.getForObject(url, QueueSnapshot[].class);
        return snapshots == null ? List.of() : List.of(snapshots);
    }
}
