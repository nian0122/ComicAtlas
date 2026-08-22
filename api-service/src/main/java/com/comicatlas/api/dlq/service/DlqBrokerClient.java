package com.comicatlas.api.dlq.service;

import java.util.List;
import java.util.Map;

public interface DlqBrokerClient {

    QueueStats queueStats(String queueName);

    List<DlqMessage> peek(String queueName, int count);

    ReplayBatch replay(
        String queueName,
        String exchange,
        String routingKey,
        int maxMessages
    );

    int purge(String queueName);

    record QueueStats(int messages, int consumers) {
    }

    record DlqMessage(
        String payload,
        String payloadEncoding,
        Map<String, Object> properties,
        int messagesRemaining
    ) {
        public DlqMessage {
            properties = Map.copyOf(properties);
        }
    }

    record ReplayBatch(
        int attempted,
        int replayed,
        int remaining,
        boolean completed,
        String error
    ) {
    }
}
