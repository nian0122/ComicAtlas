package com.comicatlas.api.admin.service;

import com.comicatlas.api.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

import static java.util.Map.entry;

@Service
@RequiredArgsConstructor
public class DlqService {

    private static final Map<String, DlqRoute> DLQ_ROUTES = Map.ofEntries(
        entry("import.task.dlq", new DlqRoute("comic.import", "task.created")),
        entry("lq.generate.dlq", new DlqRoute("comic.image", "lq.generate")),
        entry("hq.delete.dlq", new DlqRoute("comic.image", "hq.delete.requested")),
        entry("delete.task.dlq", new DlqRoute("comic.delete", "delete.requested")),
        entry("export.task.dlq", new DlqRoute("comic.export", "task.created")),
        entry("import.result.dlq", new DlqRoute("comic.import", "task.completed")),
        entry("import.failed.dlq", new DlqRoute("comic.import", "task.failed")),
        entry("lq.result.dlq", new DlqRoute("comic.image", "lq.completed")),
        entry("hq.delete.result.dlq", new DlqRoute("comic.image", "hq.delete.completed")),
        entry("delete.result.dlq", new DlqRoute("comic.delete", "delete.completed")),
        entry("export.started.result.dlq", new DlqRoute("comic.export", "task.started")),
        entry("export.completed.result.dlq", new DlqRoute("comic.export", "task.completed")),
        entry("export.failed.result.dlq", new DlqRoute("comic.export", "task.failed"))
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
            throw new BusinessException(400, "未知 DLQ: " + queueName);
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
