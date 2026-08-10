package com.comicatlas.api.admin.service;

import com.comicatlas.api.admin.service.DlqBrokerClient.QueueStats;
import com.comicatlas.api.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DlqServiceTest {

    private DlqBrokerClient brokerClient;
    private DlqService service;

    @BeforeEach
    void setUp() {
        brokerClient = mock(DlqBrokerClient.class);
        service = new DlqService(brokerClient);
    }

    @Test
    void listsEveryDeclaredDeadLetterQueue() {
        when(brokerClient.queueStats(org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(new QueueStats(0, 0));

        var queues = service.listQueues();

        // DLQ_ROUTES 冻结路由共 9 条（旧 LQ/转码 DLQ 已随管线移除）
        assertThat(queues)
            .hasSize(9)
            .extracting(DlqService.DlqQueueVO::name)
            .contains(
                "export.started.result.dlq",
                "export.completed.result.dlq",
                "export.failed.result.dlq"
            );
    }

    @Test
    void rejectsUnknownQueueBeforeCallingBroker() {
        assertThatThrownBy(() -> service.getMessages("unrelated.queue", 20))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("未知 DLQ");
        assertThatThrownBy(() -> service.replay("unrelated.queue", 100))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("未知 DLQ");
        assertThatThrownBy(() -> service.purge("unrelated.queue"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("未知 DLQ");
    }

    @Test
    void delegatesPreviewAndReplayUsingFrozenRoute() {
        when(brokerClient.peek("import.task.dlq", 20)).thenReturn(java.util.List.of());
        var replayResult = new DlqBrokerClient.ReplayBatch(1, 1, 0, true, null);
        when(brokerClient.replay(
            "import.task.dlq",
            "comic.import",
            "task.created",
            100
        )).thenReturn(replayResult);

        service.getMessages("import.task.dlq", 20);
        var result = service.replay("import.task.dlq", 100);

        verify(brokerClient).peek("import.task.dlq", 20);
        verify(brokerClient).replay(
            "import.task.dlq",
            "comic.import",
            "task.created",
            100
        );
        assertThat(result.replayed()).isEqualTo(1);
        assertThat(result.completed()).isTrue();
    }

    @Test
    void exposesMessagePropertiesWithoutWeaklyTypedJsonNodes() {
        var message = new DlqBrokerClient.DlqMessage(
            "{\"taskId\":1}",
            "string",
            Map.of("contentType", "application/json"),
            2
        );
        when(brokerClient.peek("import.task.dlq", 1)).thenReturn(java.util.List.of(message));

        var messages = service.getMessages("import.task.dlq", 1);

        assertThat(messages).containsExactly(message);
    }
}
