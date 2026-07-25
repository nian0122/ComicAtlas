package com.comicatlas.api.admin.service;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.GetResponse;
import com.rabbitmq.client.ReturnListener;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@RequiredArgsConstructor
public class RabbitDlqBrokerClient implements DlqBrokerClient {

    static final long CONFIRM_TIMEOUT_MILLIS = 5_000;

    private final RabbitTemplate rabbitTemplate;
    private final AmqpAdmin rabbitAdmin;

    @Override
    public QueueStats queueStats(String queueName) {
        var properties = rabbitAdmin.getQueueProperties(queueName);
        if (properties == null) {
            return new QueueStats(0, 0);
        }
        return new QueueStats(
            numberProperty(properties.get(RabbitAdmin.QUEUE_MESSAGE_COUNT)),
            numberProperty(properties.get(RabbitAdmin.QUEUE_CONSUMER_COUNT))
        );
    }

    @Override
    public List<DlqMessage> peek(String queueName, int count) {
        List<DlqMessage> messages = rabbitTemplate.execute(channel -> peek(channel, queueName, count));
        return messages == null ? List.of() : messages;
    }

    @Override
    public ReplayBatch replay(
            String queueName,
            String exchange,
            String routingKey,
            int maxMessages) {
        ReplayBatch result = rabbitTemplate.execute(
            channel -> replay(channel, queueName, exchange, routingKey, maxMessages)
        );
        if (result == null) {
            return new ReplayBatch(0, 0, 0, false, "RabbitMQ 未返回重放结果");
        }
        return result;
    }

    @Override
    public int purge(String queueName) {
        return rabbitAdmin.purgeQueue(queueName);
    }

    private List<DlqMessage> peek(Channel channel, String queueName, int count) throws Exception {
        var messages = new ArrayList<DlqMessage>(count);
        long lastDeliveryTag = 0;
        try {
            for (int index = 0; index < count; index++) {
                GetResponse response = channel.basicGet(queueName, false);
                if (response == null) {
                    break;
                }
                lastDeliveryTag = response.getEnvelope().getDeliveryTag();
                messages.add(toMessage(response));
            }
            return List.copyOf(messages);
        } finally {
            if (lastDeliveryTag > 0) {
                channel.basicNack(lastDeliveryTag, true, true);
            }
        }
    }

    private ReplayBatch replay(
            Channel channel,
            String queueName,
            String exchange,
            String routingKey,
            int maxMessages) throws Exception {
        var returned = new AtomicBoolean(false);
        ReturnListener returnListener = channel.addReturnListener(message -> returned.set(true));
        int attempted = 0;
        int replayed = 0;
        int remaining = 0;
        try {
            channel.confirmSelect();
            while (attempted < maxMessages) {
                GetResponse response = channel.basicGet(queueName, false);
                if (response == null) {
                    return new ReplayBatch(attempted, replayed, 0, true, null);
                }
                attempted++;
                remaining = response.getMessageCount();
                returned.set(false);
                try {
                    channel.basicPublish(
                        exchange,
                        routingKey,
                        true,
                        response.getProps(),
                        response.getBody()
                    );
                    boolean confirmed = channel.waitForConfirms(CONFIRM_TIMEOUT_MILLIS);
                    if (!confirmed) {
                        requeue(channel, response);
                        return failed(attempted, replayed, remaining, "发布确认失败，原消息已重新入队");
                    }
                    if (returned.get()) {
                        requeue(channel, response);
                        return failed(attempted, replayed, remaining, "目标路由不可路由，原消息已重新入队");
                    }
                    channel.basicAck(response.getEnvelope().getDeliveryTag(), false);
                    replayed++;
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    requeue(channel, response);
                    return failed(attempted, replayed, remaining, "等待发布确认被中断，原消息已重新入队");
                } catch (TimeoutException | AmqpException exception) {
                    requeue(channel, response);
                    return failed(attempted, replayed, remaining, "发布确认超时或失败，原消息已重新入队");
                } catch (Exception exception) {
                    requeue(channel, response);
                    return failed(attempted, replayed, remaining, "消息重放失败，原消息已重新入队");
                }
            }
            return new ReplayBatch(attempted, replayed, remaining, remaining == 0, null);
        } finally {
            channel.removeReturnListener(returnListener);
        }
    }

    private static ReplayBatch failed(
            int attempted,
            int replayed,
            int remaining,
            String error) {
        return new ReplayBatch(attempted, replayed, remaining + 1, false, error);
    }

    private static void requeue(Channel channel, GetResponse response) throws Exception {
        channel.basicNack(response.getEnvelope().getDeliveryTag(), false, true);
    }

    private static DlqMessage toMessage(GetResponse response) {
        byte[] body = response.getBody();
        String payload;
        String encoding;
        try {
            payload = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(body))
                .toString();
            encoding = "string";
        } catch (CharacterCodingException exception) {
            payload = Base64.getEncoder().encodeToString(body);
            encoding = "base64";
        }
        return new DlqMessage(
            payload,
            encoding,
            properties(response.getProps()),
            response.getMessageCount()
        );
    }

    private static Map<String, Object> properties(AMQP.BasicProperties properties) {
        var values = new LinkedHashMap<String, Object>();
        putIfPresent(values, "contentType", properties.getContentType());
        putIfPresent(values, "contentEncoding", properties.getContentEncoding());
        putIfPresent(values, "messageId", properties.getMessageId());
        putIfPresent(values, "correlationId", properties.getCorrelationId());
        putIfPresent(values, "type", properties.getType());
        putIfPresent(values, "timestamp", properties.getTimestamp());
        if (properties.getHeaders() != null) {
            values.put("headers", Collections.unmodifiableMap(properties.getHeaders()));
        }
        return Collections.unmodifiableMap(values);
    }

    private static void putIfPresent(Map<String, Object> values, String key, Object value) {
        if (value != null) {
            values.put(key, value);
        }
    }

    private static int numberProperty(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }
}
