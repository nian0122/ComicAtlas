package com.comicatlas.common.mq;

import com.rabbitmq.client.Channel;
import com.comicatlas.common.mq.MqConsumerSupport.FailurePolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MqConsumerSupportTest {

    private final MqConsumerSupport support = new MqConsumerSupport();

    @Test
    void success_acksWithNoReject() throws Exception {
        Channel channel = mock(Channel.class);
        support.consume(channel, 1L, "label", () -> { });
        verify(channel).basicAck(1L, false);
        verify(channel, never()).basicReject(anyLong(), anyBoolean());
    }

    @Test
    void failure_rejectToDlq_runsOnFailure() throws Exception {
        Channel channel = mock(Channel.class);
        support.consume(channel, 1L, "label",
                () -> { throw new IllegalStateException("boom"); },
                e -> { }, FailurePolicy.REJECT_TO_DLQ);
        verify(channel).basicReject(1L, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    @Test
    void failure_requeue_rejectsWithTrue() throws Exception {
        Channel channel = mock(Channel.class);
        support.consume(channel, 1L, "label",
                () -> { throw new IllegalStateException("boom"); },
                null, FailurePolicy.REQUEUE);
        verify(channel).basicReject(1L, true);
    }

    @Test
    void failure_ackAfterCallback_acksAndNoReject() throws Exception {
        Channel channel = mock(Channel.class);
        support.consume(channel, 1L, "label",
                () -> { throw new IllegalStateException("boom"); },
                e -> { }, FailurePolicy.ACK_AFTER_CALLBACK);
        verify(channel).basicAck(1L, false);
        verify(channel, never()).basicReject(anyLong(), anyBoolean());
    }

    @Test
    void interrupted_restoresFlagAndNeverAcksOrRejects() throws Exception {
        Channel channel = mock(Channel.class);
        Thread.currentThread().interrupt();
        try {
            support.consume(channel, 1L, "label",
                    () -> { throw new InterruptedException("interrupt"); },
                    e -> { }, FailurePolicy.REJECT_TO_DLQ);
        } finally {
            assertTrue(Thread.interrupted(), "中断标志必须被恢复且被消费");
        }
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        verify(channel, never()).basicReject(anyLong(), anyBoolean());
    }

    @Test
    void onFailureThrows_doesNotMaskOriginalAndStillRejects() throws Exception {
        Channel channel = mock(Channel.class);
        support.consume(channel, 1L, "label",
                () -> { throw new IllegalStateException("original"); },
                e -> { throw new IllegalStateException("onFailure boom"); },
                FailurePolicy.REJECT_TO_DLQ);
        verify(channel).basicReject(1L, false);
    }

    @Test
    void ackAfterCallback_callbackFailure_requeuesOriginalMessage() throws Exception {
        Channel channel = mock(Channel.class);
        support.consume(channel, 1L, "label",
                () -> { throw new IllegalStateException("original"); },
                e -> { throw new IllegalStateException("publish failed"); },
                FailurePolicy.ACK_AFTER_CALLBACK);
        verify(channel).basicReject(1L, true);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    @Test
    void ackThrows_logsAndDoesNotPropagate() throws Exception {
        Channel channel = mock(Channel.class);
        doThrow(new java.io.IOException("ack fail")).when(channel).basicAck(anyLong(), anyBoolean());
        assertDoesNotThrow(() -> support.consume(channel, 1L, "label", () -> { }));
    }

    @Test
    void interruptDoesNotRunOnFailure() throws Exception {
        Channel channel = mock(Channel.class);
        Thread.currentThread().interrupt();
        try {
            support.consume(channel, 1L, "label",
                    () -> { throw new InterruptedException(); },
                    e -> fail("onFailure 不应在中断时执行"),
                    FailurePolicy.ACK_AFTER_CALLBACK);
        } finally {
            Thread.interrupted();
        }
    }
}
