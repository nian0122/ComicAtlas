package com.comicatlas.api.admin.service;

import com.comicatlas.common.constant.MqExchanges;
import com.comicatlas.common.constant.MqQueues;
import com.comicatlas.common.constant.MqRoutingKeys;
import com.comicatlas.contract.common.constant.HttpStatusCodes;
import com.comicatlas.contract.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

import static java.util.Map.entry;

@Service
@RequiredArgsConstructor
public class DlqService {

    /** RabbitMQ 队列命名约定：DLQ 队列名 = 主队列名 + 本后缀。 */
    private static final String DLQ_NAME_SUFFIX = ".dlq";
    /** 由 DLQ 名推导主队列名时替换为的后缀。 */
    private static final String ORIGINAL_QUEUE_SUFFIX = ".queue";

    private static final Map<String, DlqRoute> DLQ_ROUTES = Map.ofEntries(
        entry(MqQueues.IMPORT_TASK_DLQ, new DlqRoute(MqExchanges.IMPORT, MqRoutingKeys.TASK_CREATED)),
        entry(MqQueues.EXPORT_TASK_DLQ, new DlqRoute(MqExchanges.EXPORT, MqRoutingKeys.TASK_CREATED)),
        entry(MqQueues.IMPORT_RESULT_DLQ, new DlqRoute(MqExchanges.IMPORT, MqRoutingKeys.TASK_COMPLETED)),
        entry(MqQueues.IMPORT_FAILED_DLQ, new DlqRoute(MqExchanges.IMPORT, MqRoutingKeys.TASK_FAILED)),
        entry(MqQueues.EXPORT_STARTED_RESULT_DLQ, new DlqRoute(MqExchanges.EXPORT, MqRoutingKeys.TASK_STARTED)),
        entry(MqQueues.EXPORT_COMPLETED_RESULT_DLQ, new DlqRoute(MqExchanges.EXPORT, MqRoutingKeys.TASK_COMPLETED)),
        entry(MqQueues.EXPORT_FAILED_RESULT_DLQ, new DlqRoute(MqExchanges.EXPORT, MqRoutingKeys.TASK_FAILED))
    );

    private final DlqBrokerClient brokerClient;

    public List<DlqQueueVO> listQueues() {
        return DLQ_ROUTES.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(routeEntry -> toQueueView(routeEntry.getKey(), routeEntry.getValue()))
            .toList();
    }

    public List<DlqBrokerClient.DlqMessage> getMessages(String queueName, int count) {
        requireRoute(queueName);
        return brokerClient.peek(queueName, count);
    }

    public ReplayResult replay(String queueName, int maxMessages) {
        DlqRoute route = requireRoute(queueName);
        DlqBrokerClient.ReplayBatch result = brokerClient.replay(
            queueName,
            route.exchange(),
            route.routingKey(),
            maxMessages
        );
        return new ReplayResult(
            queueName,
            result.attempted(),
            result.replayed(),
            result.remaining(),
            result.completed(),
            result.error()
        );
    }

    public PurgeResult purge(String queueName) {
        requireRoute(queueName);
        return new PurgeResult(queueName, brokerClient.purge(queueName));
    }

    private DlqQueueVO toQueueView(String name, DlqRoute route) {
        DlqBrokerClient.QueueStats stats = brokerClient.queueStats(name);
        return new DlqQueueVO(
            name,
            route.exchange(),
            route.routingKey(),
            name.replace(DLQ_NAME_SUFFIX, ORIGINAL_QUEUE_SUFFIX),
            stats.messages(),
            stats.consumers()
        );
    }

    private static DlqRoute requireRoute(String queueName) {
        DlqRoute route = DLQ_ROUTES.get(queueName);
        if (route == null) {
            throw new BusinessException(HttpStatusCodes.BAD_REQUEST, "未知 DLQ: " + queueName);
        }
        return route;
    }

    public record DlqRoute(String exchange, String routingKey) {
    }

    public record DlqQueueVO(
        String name,
        String exchange,
        String routingKey,
        String originalQueue,
        int messages,
        int consumers
    ) {
    }

    public record ReplayResult(
        String queue,
        int attempted,
        int replayed,
        int remaining,
        boolean completed,
        String error
    ) {
    }

    public record PurgeResult(String queue, int purged) {
    }
}
