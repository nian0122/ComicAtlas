package com.comicatlas.worker.event;

import com.comicatlas.common.event.CancelTaskEvent;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * CancelHandler Redis 化单元测试。
 */
@ExtendWith(MockitoExtension.class)
class CancelHandlerTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private Channel channel;

    @Test
    void handle_writesRedisKeyAndAcks() throws Exception {
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(ops);
        CancelHandler handler = new CancelHandler(redisTemplate);

        CancelTaskEvent event = new CancelTaskEvent(UUID.randomUUID(), Instant.now(), 123L, 1L);
        handler.handle(event, channel, 7L);

        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(ops).set(eq("import:cancel:123"), eq("1"), ttlCaptor.capture());
        assertTrue(ttlCaptor.getValue().compareTo(Duration.ofDays(7)) <= 0, "TTL 应不超过 7 天");
        verify(channel).basicAck(eq(7L), eq(false));
    }

    @Test
    void isCancelled_readsRedis() {
        when(redisTemplate.hasKey("import:cancel:456")).thenReturn(true, false);
        CancelHandler handler = new CancelHandler(redisTemplate);

        assertTrue(handler.isCancelled(456L));
        assertFalse(handler.isCancelled(456L));
        verify(redisTemplate, times(2)).hasKey("import:cancel:456");
    }
}
