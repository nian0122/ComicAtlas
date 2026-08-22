package com.comicatlas.api.management.service;

import com.comicatlas.api.management.service.RabbitManagementClient;
import com.comicatlas.api.management.service.RabbitManagementClient.QueueSnapshot;
import com.comicatlas.common.dto.MqStatsDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MqStatsServiceTest {

    private RabbitManagementClient managementClient;
    private MqStatsService service;

    @BeforeEach
    void setUp() {
        managementClient = mock(RabbitManagementClient.class);
        service = new MqStatsService(managementClient);
    }

    @Test
    void aggregatesDeadLetterTotalsAndMainQueueBacklogSeparately() {
        when(managementClient.listQueues()).thenReturn(List.of(
            new QueueSnapshot("video.transcode.result.queue", 175, 175, 0),
            new QueueSnapshot("import.storage.finalize.requested.queue", 19, 18, 1),
            new QueueSnapshot("video.metadata.fix.dlq", 4, 4, 0),
            new QueueSnapshot("video.transcode.dlq", 2, 2, 0),
            new QueueSnapshot("lq.generate.queue", 0, 0, 1),
            new QueueSnapshot("import.result.queue", 7, 0, 1)
        ));

        var stats = service.stats();

        assertThat(stats.available()).isTrue();
        assertThat(stats.dlqTotal()).isEqualTo(6);
        assertThat(stats.dlqQueues()).isEqualTo(2);
        assertThat(stats.queuedTotal()).isEqualTo(193);
        assertThat(stats.queues())
            .extracting(MqStatsDTO.MqQueueStat::name)
            .containsExactly(
                "video.transcode.result.queue",
                "import.storage.finalize.requested.queue",
                "video.metadata.fix.dlq",
                "video.transcode.dlq"
            );
        assertThat(stats.queues())
            .filteredOn(queue -> queue.name().equals("import.result.queue"))
            .isEmpty();
    }

    @Test
    void ignoresUnacknowledgedInFlightMessagesForBacklog() {
        when(managementClient.listQueues()).thenReturn(List.of(
            new QueueSnapshot("management.result.queue", 3, 0, 1)
        ));

        var stats = service.stats();

        assertThat(stats.queuedTotal()).isZero();
        assertThat(stats.queues()).isEmpty();
    }

    @Test
    void returnsUnavailableStatsWhenManagementApiFails() {
        when(managementClient.listQueues()).thenThrow(new RuntimeException("connect timeout"));

        var stats = service.stats();

        assertThat(stats.available()).isFalse();
        assertThat(stats.dlqTotal()).isZero();
        assertThat(stats.queuedTotal()).isZero();
        assertThat(stats.queues()).isEmpty();
    }

    @Test
    void reportsZeroBacklogWhenEveryQueueIsEmpty() {
        when(managementClient.listQueues()).thenReturn(List.of(
            new QueueSnapshot("import.task.queue", 0, 0, 1)
        ));

        var stats = service.stats();

        assertThat(stats.available()).isTrue();
        assertThat(stats.dlqTotal()).isZero();
        assertThat(stats.queuedTotal()).isZero();
        assertThat(stats.queues()).isEmpty();
    }
}
