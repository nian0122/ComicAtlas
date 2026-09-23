package com.comicatlas.api.task.adapter;

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
@lombok.Getter
public class RabbitManagementClient {

    /** 队列快照：仅请求统计所需列，避免拉取完整大响应。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @lombok.Getter
    public static class QueueSnapshot {
        private final String name;
        private final long messages;
        @JsonProperty("messages_ready") private final long messagesReady;
        private final int consumers;
        public QueueSnapshot(String name, long messages, long messagesReady, int consumers) {
            this.name = name;
            this.messages = messages;
            this.messagesReady = messagesReady;
            this.consumers = consumers;
        }
        public String name() { return name; }
        public long messages() { return messages; }
        public long messagesReady() { return messagesReady; }
        public int consumers() { return consumers; }
        @Override
        public boolean equals(Object other) {
            if (this == other) { return true; }
            if (!(other instanceof QueueSnapshot)) { return false; }
            QueueSnapshot that = (QueueSnapshot) other;
            return java.util.Objects.equals(name, that.name) && java.util.Objects.equals(messages, that.messages) && java.util.Objects.equals(messagesReady, that.messagesReady) && java.util.Objects.equals(consumers, that.consumers);
        }
        @Override
        public int hashCode() { return java.util.Objects.hash(name, messages, messagesReady, consumers); }
        @Override
        public String toString() { return "QueueSnapshot[" + "name=" + name + ", " + "messages=" + messages + ", " + "messagesReady=" + messagesReady + ", " + "consumers=" + consumers + "]"; }
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
