package com.comicatlas.worker.task.publisher;

import com.comicatlas.common.constant.MqExchanges;
import com.comicatlas.common.constant.MqRoutingKeys;
import com.comicatlas.common.event.ManagementCommandFailedEvent;
import com.comicatlas.common.event.ManagementCommandRequestedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 管理命令结果发布器单元测试：FAILED 事件 errorMessage 必须截断到安全长度，
 * 防止超长错误文本（如内嵌外部进程 stdout）溢出 API 端 error_message 列（varchar(4096)），
 * 导致结果事件进 DLQ、管理任务 item 永久停在 QUEUED。
 */
@DisplayName("ManagementCommandPublisherTest — FAILED errorMessage 截断")
class ManagementCommandPublisherTest {

    private final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
    private final ManagementCommandPublisher publisher = new ManagementCommandPublisher(rabbitTemplate);

    private static ManagementCommandRequestedEvent cmd() {
        return new ManagementCommandRequestedEvent(
                UUID.randomUUID(), Instant.now(), 1, 1L, 1L, 1,
                "LQ_GENERATE", "CHAPTER", 42L);
    }

    @Test
    @DisplayName("超长 errorMessage 被截断且保留截断标记")
    void failed_oversizedError_isTruncated() {
        String huge = "x".repeat(10_000);

        publisher.failed(cmd(), huge);

        ArgumentCaptor<ManagementCommandFailedEvent> captor = ArgumentCaptor.forClass(ManagementCommandFailedEvent.class);
        verify(rabbitTemplate).convertAndSend(eq(MqExchanges.MANAGEMENT),
                eq(MqRoutingKeys.COMMAND_FAILED), captor.capture());
        ManagementCommandFailedEvent event = captor.getValue();
        assertThat(event.errorMessage()).as("超长 errorMessage 应被截断到安全长度")
                .hasSizeLessThanOrEqualTo(2_050);
        assertThat(event.errorMessage()).contains("已截断");
        assertThat(event.errorMessage()).startsWith("xxx");
    }

    @Test
    @DisplayName("正常长度 errorMessage 原样发布")
    void failed_normalError_passedThrough() {
        String message = "LQ 生成失败页: [3, 7]";

        publisher.failed(cmd(), message);

        ArgumentCaptor<ManagementCommandFailedEvent> captor = ArgumentCaptor.forClass(ManagementCommandFailedEvent.class);
        verify(rabbitTemplate).convertAndSend(eq(MqExchanges.MANAGEMENT),
                eq(MqRoutingKeys.COMMAND_FAILED), captor.capture());
        assertThat(captor.getValue().errorMessage()).isEqualTo(message);
    }

    @Test
    @DisplayName("null errorMessage 原样发布（不抛异常）")
    void failed_nullError_passedThrough() {
        publisher.failed(cmd(), null);

        ArgumentCaptor<ManagementCommandFailedEvent> captor = ArgumentCaptor.forClass(ManagementCommandFailedEvent.class);
        verify(rabbitTemplate).convertAndSend(eq(MqExchanges.MANAGEMENT),
                eq(MqRoutingKeys.COMMAND_FAILED), captor.capture());
        assertThat(captor.getValue().errorMessage()).isNull();
    }
}
