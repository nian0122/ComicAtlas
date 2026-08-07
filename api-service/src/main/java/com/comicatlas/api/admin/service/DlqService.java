package com.comicatlas.api.admin.service;

import com.comicatlas.api.common.constant.HttpStatusCodes;
import com.comicatlas.api.common.exception.BusinessException;
import com.comicatlas.common.constant.MqExchanges;
import com.comicatlas.common.constant.MqQueues;
import com.comicatlas.common.constant.MqRoutingKeys;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

import static java.util.Map.entry;

@Service
@RequiredArgsConstructor
public class DlqService {

    private static final Map<String, DlqRoute> DLQ_ROUTES = Map.ofEntries(
        entry(MqQueues.IMPORT_TASK_DLQ, new DlqRoute(MqExchanges.IMPORT, MqRoutingKeys.TASK_CREATED)),
        entry(MqQueues.LQ_GENERATE_DLQ, new DlqRoute(MqExchanges.IMAGE, MqRoutingKeys.LQ_GENERATE)),
        entry(MqQueues.HQ_DELETE_DLQ, new DlqRoute(MqExchanges.IMAGE, MqRoutingKeys.HQ_DELETE_REQUESTED)),
        entry(MqQueues.DELETE_TASK_DLQ, new DlqRoute(MqExchanges.DELETE, MqRoutingKeys.DELETE_REQUESTED)),
        entry(MqQueues.EXPORT_TASK_DLQ, new DlqRoute(MqExchanges.EXPORT, MqRoutingKeys.TASK_CREATED)),
        entry(MqQueues.IMPORT_RESULT_DLQ, new DlqRoute(MqExchanges.IMPORT, MqRoutingKeys.TASK_COMPLETED)),
        entry(MqQueues.IMPORT_FAILED_DLQ, new DlqRoute(MqExchanges.IMPORT, MqRoutingKeys.TASK_FAILED)),
        entry(MqQueues.LQ_RESULT_DLQ, new DlqRoute(MqExchanges.IMAGE, MqRoutingKeys.LQ_COMPLETED)),
        entry(MqQueues.HQ_DELETE_RESULT_DLQ, new DlqRoute(MqExchanges.IMAGE, MqRoutingKeys.HQ_DELETE_COMPLETED)),
        entry(MqQueues.DELETE_RESULT_DLQ, new DlqRoute(MqExchanges.DELETE, MqRoutingKeys.DELETE_COMPLETED)),
        entry(MqQueues.EXPORT_STARTED_RESULT_DLQ, new DlqRoute(MqExchanges.EXPORT, MqRoutingKeys.TASK_STARTED)),
        entry(MqQueues.EXPORT_COMPLETED_RESULT_DLQ, new DlqRoute(MqExchanges.EXPORT, MqRoutingKeys.TASK_COMPLETED)),
        entry(MqQueues.EXPORT_FAILED_RESULT_DLQ, new DlqRoute(MqExchanges.EXPORT, MqRoutingKeys.TASK_FAILED))
    );

    private final DlqBrokerClient brokerClient;

    public List<DlqQueueVO> listQueues() {
        return DLQ_ROUTES.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(entry -> queueView(entry.getKey(), entry.getValue()))
            .toList();
    }

    public List<DlqBrokerClient.DlqMessage> getMessages(String queueName, int count) {
        requireRoute(queueName);
        return brokerClient.peek(queueName, count);
    }

    public ReplayResult replay(String queueName, int maxMessages) {
        DlqRoute route = requireRoute(queueName);
        var result = brokerClient.replay(
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

    private DlqQueueVO queueView(String name, DlqRoute route) {
        var stats = brokerClient.queueStats(name);
        return new DlqQueueVO(
            name,
            route.exchange(),
            route.routingKey(),
            name.replace(".dlq", ".queue"),
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
