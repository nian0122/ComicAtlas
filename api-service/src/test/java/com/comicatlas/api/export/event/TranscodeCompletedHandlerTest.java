package com.comicatlas.api.export.event;

import com.comicatlas.api.comic.entity.Media;
import com.comicatlas.api.comic.mapper.MediaMapper;
import com.comicatlas.common.event.VideoTranscodeCompletedEvent;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TranscodeCompletedHandlerTest {

    @Mock
    private MediaMapper mediaMapper;

    @Mock
    private Channel channel;

    private TranscodeCompletedHandler handler;

    @BeforeEach
    void setUp() {
        handler = new TranscodeCompletedHandler(mediaMapper);
    }

    // ======================== 正向流程 ========================

    @Test
    void PENDING_to_DONE_withAllFields() throws Exception {
        // Given: PENDING 状态的页面
        long pageId = 1L;
        long comicId = 100L;

        Media media = new Media();
        media.setId(pageId);
        media.setTranscodeStatus("PENDING");
        when(mediaMapper.selectById(pageId)).thenReturn(media);
        when(mediaMapper.updateById(any(Media.class))).thenReturn(1);

        VideoTranscodeCompletedEvent event = new VideoTranscodeCompletedEvent(
                UUID.randomUUID(), Instant.now(), pageId, comicId,
                "100/1/transcoded.mp4", "mp4", "h264", "aac", 2048000L);

        // When
        handler.handleCompleted(event, channel, 1L);

        // Then: 页面变为 DONE，字段全部更新
        assertEquals("DONE", media.getTranscodeStatus());
        assertEquals("100/1/transcoded.mp4", media.getHqPath());
        assertEquals("mp4", media.getContainer());
        assertEquals("h264", media.getVideoCodec());
        assertEquals("aac", media.getAudioCodec());
        assertEquals(Long.valueOf(2048000L), media.getFileSize());

        verify(mediaMapper).updateById(media);
        verify(channel).basicAck(1L, false);
    }

    // ======================== 幂等性 — DONE ========================

    @Test
    void DONE_page_idempotent_noChange() throws Exception {
        // Given: 已经 DONE 的页面
        long pageId = 2L;

        Media media = new Media();
        media.setId(pageId);
        media.setTranscodeStatus("DONE");
        media.setHqPath("100/2/original.mp4");
        when(mediaMapper.selectById(pageId)).thenReturn(media);

        VideoTranscodeCompletedEvent event = new VideoTranscodeCompletedEvent(
                UUID.randomUUID(), Instant.now(), pageId, 100L,
                "100/2/transcoded.mp4", "mp4", "h264", "aac", 999L);

        // When
        handler.handleCompleted(event, channel, 1L);

        // Then: 不更新，直接 ACK
        verify(mediaMapper, never()).updateById(any(Media.class));
        verify(channel).basicAck(1L, false);

        // 原字段不被覆盖
        assertEquals("DONE", media.getTranscodeStatus());
        assertEquals("100/2/original.mp4", media.getHqPath());
    }

    // ======================== 幂等性 — FAILED ========================

    @Test
    void FAILED_page_idempotent_noOverwrite() throws Exception {
        // Given: FAILED 状态的页面（不会用 completed 覆盖 FAILED）
        long pageId = 3L;

        Media media = new Media();
        media.setId(pageId);
        media.setTranscodeStatus("FAILED");
        when(mediaMapper.selectById(pageId)).thenReturn(media);

        VideoTranscodeCompletedEvent event = new VideoTranscodeCompletedEvent(
                UUID.randomUUID(), Instant.now(), pageId, 100L,
                "100/3/transcoded.mp4", "mp4", "h264", "aac", 999L);

        // When
        handler.handleCompleted(event, channel, 1L);

        // Then: 不更新，直接 ACK
        verify(mediaMapper, never()).updateById(any(Media.class));
        verify(channel).basicAck(1L, false);
        assertEquals("FAILED", media.getTranscodeStatus());
    }

    // ======================== 不存在的 pageId ========================

    @Test
    void nonExistent_pageId_ackWithoutError() throws Exception {
        // Given: 不存在的页面
        long pageId = 999L;
        when(mediaMapper.selectById(pageId)).thenReturn(null);

        VideoTranscodeCompletedEvent event = new VideoTranscodeCompletedEvent(
                UUID.randomUUID(), Instant.now(), pageId, 100L,
                "100/999/transcoded.mp4", "mp4", "h264", "aac", 999L);

        // When
        handler.handleCompleted(event, channel, 1L);

        // Then: 不崩溃，直接 ACK
        verify(mediaMapper, never()).updateById(any(Media.class));
        verify(channel).basicAck(1L, false);
    }

    // ======================== 非 PENDING 的中间状态 ========================

    @Test
    void NOT_NEEDED_page_ackWithoutChange() throws Exception {
        // Given: NOT_NEEDED 页面（从未进入 PENDING）
        long pageId = 5L;

        Media media = new Media();
        media.setId(pageId);
        media.setTranscodeStatus("NOT_NEEDED");
        when(mediaMapper.selectById(pageId)).thenReturn(media);

        VideoTranscodeCompletedEvent event = new VideoTranscodeCompletedEvent(
                UUID.randomUUID(), Instant.now(), pageId, 100L,
                "100/5/transcoded.mp4", "mp4", "h264", "aac", 999L);

        // When
        handler.handleCompleted(event, channel, 1L);

        // Then: 不更新
        verify(mediaMapper, never()).updateById(any(Media.class));
        verify(channel).basicAck(1L, false);
    }
}
