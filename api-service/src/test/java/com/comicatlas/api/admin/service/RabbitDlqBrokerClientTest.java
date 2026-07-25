package com.comicatlas.api.admin.service;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Envelope;
import com.rabbitmq.client.GetResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.ChannelCallback;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

class RabbitDlqBrokerClientTest {

    private RabbitTemplate rabbitTemplate;
    private AmqpAdmin rabbitAdmin;
    private Channel channel;
    private RabbitDlqBrokerClient client;

    @BeforeEach
    void setUp() {
        rabbitTemplate = mock(RabbitTemplate.class);
        rabbitAdmin = mock(AmqpAdmin.class);
        channel = mock(Channel.class);
        client = new RabbitDlqBrokerClient(rabbitTemplate, rabbitAdmin);
        when(rabbitTemplate.execute(any())).thenAnswer(invocation -> {
            ChannelCallback<?> callback = invocation.getArgument(0);
            return callback.doInRabbit(channel);
        });
    }

    @Test
    void previewUsesManualAckAndRequeuesEveryDelivery() throws Exception {
        var first = response(11, "{\"id\":1}", 1);
        var second = response(12, "{\"id\":2}", 0);
        when(channel.basicGet("import.task.dlq", false))
            .thenReturn(first, second);

        var messages = client.peek("import.task.dlq", 2);

        assertThat(messages).hasSize(2);
        verify(channel, times(2)).basicGet("import.task.dlq", false);
        verify(channel).basicNack(12, true, true);
        verify(channel, never()).basicAck(any(Long.class), eq(false));
    }

    @Test
    void replayPreservesBodyAndPropertiesThenAcknowledgesAfterConfirm() throws Exception {
        var properties = new AMQP.BasicProperties.Builder()
            .contentType("application/json")
            .messageId("message-42")
            .headers(java.util.Map.of("x-source", "worker"))
            .build();
        var body = "{\"taskId\":42}".getBytes(StandardCharsets.UTF_8);
        when(channel.basicGet("import.task.dlq", false))
            .thenReturn(new GetResponse(new Envelope(21, false, "", ""), properties, body, 0))
            .thenReturn(null);
        when(channel.waitForConfirms(RabbitDlqBrokerClient.CONFIRM_TIMEOUT_MILLIS))
            .thenReturn(true);

        var result = client.replay(
            "import.task.dlq",
            "comic.import",
            "task.created",
            10
        );

        verify(channel).basicPublish("comic.import", "task.created", true, properties, body);
        verify(channel).basicAck(21, false);
        verify(channel, never()).basicNack(21, false, true);
        assertThat(result.replayed()).isEqualTo(1);
        assertThat(result.completed()).isTrue();
    }

    @Test
    void replayRequeuesOriginalMessageWhenPublishIsNotConfirmed() throws Exception {
        when(channel.basicGet("import.task.dlq", false))
            .thenReturn(response(31, "{\"id\":31}", 0))
            .thenReturn(null);
        when(channel.waitForConfirms(RabbitDlqBrokerClient.CONFIRM_TIMEOUT_MILLIS))
            .thenReturn(false);

        var result = client.replay(
            "import.task.dlq",
            "comic.import",
            "task.created",
            10
        );

        verify(channel, never()).basicAck(31, false);
        verify(channel).basicNack(31, false, true);
        assertThat(result.replayed()).isZero();
        assertThat(result.completed()).isFalse();
        assertThat(result.error()).contains("发布确认失败");
    }

    @Test
    void replayRequeuesOriginalMessageWhenMandatoryPublishIsReturned() throws Exception {
        when(channel.basicGet("import.task.dlq", false))
            .thenReturn(response(41, "{\"id\":41}", 0))
            .thenReturn(null);
        when(channel.waitForConfirms(RabbitDlqBrokerClient.CONFIRM_TIMEOUT_MILLIS))
            .thenReturn(true);
        var returnCallback = new AtomicReference<com.rabbitmq.client.ReturnCallback>();
        doAnswer(invocation -> {
            returnCallback.set(invocation.getArgument(0, com.rabbitmq.client.ReturnCallback.class));
            return mock(com.rabbitmq.client.ReturnListener.class);
        }).when(channel).addReturnListener(any(com.rabbitmq.client.ReturnCallback.class));
        doAnswer(invocation -> {
            returnCallback.get().handle(new com.rabbitmq.client.Return(
                312,
                "NO_ROUTE",
                "comic.import",
                "task.created",
                new AMQP.BasicProperties(),
                new byte[0]
            ));
            return null;
        }).when(channel).basicPublish(
            eq("comic.import"),
            eq("task.created"),
            eq(true),
            any(AMQP.BasicProperties.class),
            any(byte[].class)
        );

        var result = client.replay(
            "import.task.dlq",
            "comic.import",
            "task.created",
            10
        );

        verify(channel, never()).basicAck(41, false);
        verify(channel).basicNack(41, false, true);
        assertThat(result.error()).contains("不可路由");
    }

    private static GetResponse response(long deliveryTag, String body, int remaining) {
        return new GetResponse(
            new Envelope(deliveryTag, false, "", ""),
            new AMQP.BasicProperties.Builder().contentType("application/json").build(),
            body.getBytes(StandardCharsets.UTF_8),
            remaining
        );
    }
}
