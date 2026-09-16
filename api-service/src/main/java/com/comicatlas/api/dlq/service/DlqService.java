package com.comicatlas.api.dlq.service;

import java.util.List;

/** DLQ 管理应用服务契约。 */
public interface DlqService {
    List<DlqQueueVO> listQueues();

    List<DlqBrokerClient.DlqMessage> getMessages(String queueName, int count);

    ReplayResult replay(String queueName, int maxMessages);

    PurgeResult purge(String queueName);

    record DlqRoute(String exchange, String routingKey) {
    }

    record DlqQueueVO(String name, String exchange, String routingKey,
                      String originalQueue, int messages, int consumers) {
    }

    record ReplayResult(String queue, int attempted, int replayed, int remaining,
                        boolean completed, String error) {
    }

    record PurgeResult(String queue, int purged) {
    }
}
