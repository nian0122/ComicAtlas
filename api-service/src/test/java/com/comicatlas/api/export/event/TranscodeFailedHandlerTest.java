package com.comicatlas.api.export.event;

import com.comicatlas.api.comic.entity.Media;
import com.comicatlas.api.comic.mapper.MediaMapper;
import com.comicatlas.api.common.enums.TranscodeStatus;
import com.comicatlas.common.event.VideoTranscodeFailedEvent;
import com.comicatlas.common.mq.MqConsumerSupport;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.annotation.RabbitListener;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TranscodeFailedHandlerTest {

    @Mock
    private MediaMapper mediaMapper;

    @Mock
    private Channel channel;

    private TranscodeFailedHandler handler;

    @BeforeEach
    void setUp() {
        handler = new TranscodeFailedHandler(mediaMapper, new MqConsumerSupport());
    }

    // ======================== 正向流程 ========================

    @Test
    void PENDING_to_FAILED() throws Exception {
        // Given: PENDING 状态的页面
        long pageId = 1L;

        Media media = new Media();
        media.setId(pageId);
        media.setTranscodeStatus(TranscodeStatus.QUEUED);
        when(mediaMapper.selectById(pageId)).thenReturn(media);
        when(mediaMapper.updateById(any(Media.class))).thenReturn(1);

        VideoTranscodeFailedEvent event = new VideoTranscodeFailedEvent(
                UUID.randomUUID(), Instant.now(), pageId, 100L,
                "ffmpeg returned exit code 1");

        // When
        handler.handleFailed(event, channel, 1L);

        // Then: 变为 FAILED
        assertEquals(TranscodeStatus.FAILED, media.getTranscodeStatus());
        verify(mediaMapper).updateById(media);
        verify(channel).basicAck(1L, false);
    }

    // ======================== 幂等性 — DONE ========================

    @Test
    void DONE_page_failedEvent_idempotent_noChange() throws Exception {
        // Given: 已经是 DONE 的页面，收到 failed 事件（乱序/重复投递）
        long pageId = 2L;

        Media media = new Media();
        media.setId(pageId);
        media.setTranscodeStatus(TranscodeStatus.READY);
        when(mediaMapper.selectById(pageId)).thenReturn(media);

        VideoTranscodeFailedEvent event = new VideoTranscodeFailedEvent(
                UUID.randomUUID(), Instant.now(), pageId, 100L,
                "late delivery");

        // When
        handler.handleFailed(event, channel, 1L);

        // Then: 不更新，直接 ACK
        verify(mediaMapper, never()).updateById(any(Media.class));
        verify(channel).basicAck(1L, false);
        assertEquals(TranscodeStatus.READY, media.getTranscodeStatus());
    }

    // ======================== 幂等性 — FAILED ========================

    @Test
    void FAILED_page_failedEvent_idempotent_noChange() throws Exception {
        // Given: 已经是 FAILED
        long pageId = 3L;

        Media media = new Media();
        media.setId(pageId);
        media.setTranscodeStatus(TranscodeStatus.FAILED);
        when(mediaMapper.selectById(pageId)).thenReturn(media);

        VideoTranscodeFailedEvent event = new VideoTranscodeFailedEvent(
                UUID.randomUUID(), Instant.now(), pageId, 100L,
                "duplicate delivery");

        // When
        handler.handleFailed(event, channel, 1L);

        // Then: 不更新
        verify(mediaMapper, never()).updateById(any(Media.class));
        verify(channel).basicAck(1L, false);
    }

    // ======================== 不存在的 pageId ========================

    @Test
    void nonExistent_pageId_ackWithoutError() throws Exception {
        // Given: 不存在的页面
        long pageId = 999L;
        when(mediaMapper.selectById(pageId)).thenReturn(null);

        VideoTranscodeFailedEvent event = new VideoTranscodeFailedEvent(
                UUID.randomUUID(), Instant.now(), pageId, 100L,
                "page gone");

        // When
        handler.handleFailed(event, channel, 1L);

        // Then: 不崩溃，直接 ACK
        verify(mediaMapper, never()).updateById(any(Media.class));
        verify(channel).basicAck(1L, false);
    }

    @Test
    void 失败事件使用独立结果队列() throws Exception {
        // Given
        RabbitListener listener = TranscodeFailedHandler.class
                .getMethod("handleFailed",
                        VideoTranscodeFailedEvent.class, Channel.class, long.class)
                .getAnnotation(RabbitListener.class);

        // When / Then
        assertArrayEquals(
                new String[]{"video.transcode.failed.queue"},
                listener.queues());
    }
}
