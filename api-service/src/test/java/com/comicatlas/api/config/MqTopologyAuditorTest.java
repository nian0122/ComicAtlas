package com.comicatlas.api.config;

import com.comicatlas.api.task.service.RabbitManagementClient;
import com.comicatlas.api.task.service.RabbitManagementClient.QueueSnapshot;
import com.comicatlas.common.constant.MqQueues;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MqTopologyAuditorTest {

    private RabbitManagementClient managementClient;
    private MqTopologyAuditor auditor;

    @BeforeEach
    void setUp() {
        managementClient = mock(RabbitManagementClient.class);
        auditor = new MqTopologyAuditor(managementClient);
    }

    @Test
    void flagsQueuesOutsideContractAsZombieSortedByMessagesDesc() {
        var broker = new ArrayList<QueueSnapshot>();
        MqQueues.all().forEach(queue -> broker.add(new QueueSnapshot(queue, 0, 0, 1)));
        broker.add(new QueueSnapshot("video.transcode.result.queue", 175, 175, 0));
        broker.add(new QueueSnapshot("import.metadata.refresh.completed.queue", 5, 5, 0));
        when(managementClient.listQueues()).thenReturn(broker);

        var result = auditor.audit();

        assertThat(result.healthy()).isFalse();
        assertThat(result.zombieQueues())
            .extracting(QueueSnapshot::name)
            .containsExactly("video.transcode.result.queue", "import.metadata.refresh.completed.queue");
        assertThat(result.missingQueues()).isEmpty();
    }

    @Test
    void reportsContractQueuesMissingFromBroker() {
        when(managementClient.listQueues()).thenReturn(List.of(
            new QueueSnapshot(MqQueues.IMPORT_TASK, 0, 0, 1)
        ));

        var result = auditor.audit();

        assertThat(result.healthy()).isFalse();
        assertThat(result.missingQueues())
            .isNotEmpty()
            .doesNotContain(MqQueues.IMPORT_TASK)
            .contains(MqQueues.IMPORT_RESULT);
    }

    @Test
    void reportsHealthyWhenBrokerMatchesContract() {
        when(managementClient.listQueues()).thenReturn(
            MqQueues.all().stream().map(queue -> new QueueSnapshot(queue, 0, 0, 1)).toList());

        var result = auditor.audit();

        assertThat(result.healthy()).isTrue();
        assertThat(result.zombieQueues()).isEmpty();
        assertThat(result.missingQueues()).isEmpty();
    }

    @Test
    void auditThrowsWhenManagementApiIsUnavailable() {
        when(managementClient.listQueues()).thenThrow(new RuntimeException("connect timeout"));

        assertThatThrownBy(() -> auditor.audit()).isInstanceOf(RuntimeException.class);
    }
}
