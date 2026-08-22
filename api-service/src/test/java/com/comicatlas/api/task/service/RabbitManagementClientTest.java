package com.comicatlas.api.task.service;

import com.comicatlas.api.task.service.RabbitManagementClient.QueueSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RabbitManagementClientTest {

    private RestTemplate restTemplate;
    private RabbitManagementClient client;

    @BeforeEach
    void setUp() {
        restTemplate = mock(RestTemplate.class);
        client = new RabbitManagementClient(restTemplate);
        ReflectionTestUtils.setField(client, "host", "rabbit.local");
        ReflectionTestUtils.setField(client, "port", 15672);
    }

    @Test
    void listsQueuesFromManagementApiWithColumnFiltering() {
        QueueSnapshot[] snapshots = {
            new QueueSnapshot("import.task.queue", 5, 5, 1),
            new QueueSnapshot("video.transcode.result.queue", 175, 175, 0),
        };
        when(restTemplate.getForObject(
            "http://rabbit.local:15672/api/queues?columns=name,messages,messages_ready,consumers",
            QueueSnapshot[].class
        )).thenReturn(snapshots);

        var queues = client.listQueues();

        assertThat(queues).containsExactly(snapshots);
        verify(restTemplate).getForObject(
            "http://rabbit.local:15672/api/queues?columns=name,messages,messages_ready,consumers",
            QueueSnapshot[].class
        );
    }

    @Test
    void returnsEmptyListWhenManagementApiRespondsWithoutBody() {
        when(restTemplate.getForObject(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(Class.class)))
            .thenReturn(null);

        assertThat(client.listQueues()).isEmpty();
    }
}
