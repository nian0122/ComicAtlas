package com.comicatlas.worker.recovery.event;

import com.comicatlas.common.constant.MqExchanges;
import com.comicatlas.common.constant.MqRoutingKeys;
import com.comicatlas.common.dto.ScanResultDTO;
import com.comicatlas.common.event.DirectoryScanCompletedEvent;
import com.comicatlas.common.event.DirectoryScanFailedEvent;
import com.comicatlas.common.event.DirectoryScanRequestedEvent;
import com.comicatlas.common.mq.MqConsumerSupport;
import com.comicatlas.worker.config.WorkerConfig;
import com.comicatlas.worker.recovery.scan.DirectoryScanPreviews;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DirectoryScanHandler 单元测试。
 * <p>
 * 验证 handler 仅负责编排：委托 {@link DirectoryScanPreviews} 扫描、发布 completed/failed 事件，
 * 失败事件消息脱敏（不含宿主机绝对路径）。
 */
@ExtendWith(MockitoExtension.class)
class DirectoryScanHandlerTest {

    @Mock
    private RabbitTemplate rabbitTemplate;

    @Mock
    private MqConsumerSupport mqConsumerSupport;

    @Mock
    private DirectoryScanPreviews scanPreviews;

    @Mock
    private WorkerConfig workerConfig;

    @Mock
    private Channel channel;

    @InjectMocks
    private DirectoryScanHandler handler;

    @BeforeEach
    void setUp() {
        when(workerConfig.mapHostPathToContainer(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        // 模拟 consume 编排：执行业务动作，异常时调用失败回调（与 MqConsumerSupport 语义一致）
        doAnswer(inv -> {
            MqConsumerSupport.ConsumeAction action = inv.getArgument(3);
            MqConsumerSupport.ExceptionHandler onFailure = inv.getArgument(4);
            try {
                action.run();
            } catch (Exception e) {
                onFailure.accept(e);
            }
            return null;
        }).when(mqConsumerSupport).consume(any(), anyLong(), anyString(),
                any(MqConsumerSupport.ConsumeAction.class),
                any(MqConsumerSupport.ExceptionHandler.class),
                any(MqConsumerSupport.FailurePolicy.class));
    }

    private static DirectoryScanRequestedEvent request(Long taskId, String path) {
        return new DirectoryScanRequestedEvent(UUID.randomUUID(), Instant.now(), taskId, path);
    }

    @Test
    void handle_delegatesToPreviewsAndPublishesCompletedEvent() {
        ScanResultDTO result = new ScanResultDTO("D:/scans/root", 1, List.of());
        when(scanPreviews.scan(any(Path.class))).thenReturn(result);

        handler.handle(request(7L, "D:/scans/root"), channel, 1L);

        verify(scanPreviews).scan(Path.of("D:/scans/root"));
        ArgumentCaptor<DirectoryScanCompletedEvent> captor = ArgumentCaptor.forClass(DirectoryScanCompletedEvent.class);
        verify(rabbitTemplate).convertAndSend(eq(MqExchanges.SCAN), eq(MqRoutingKeys.SCAN_COMPLETED), captor.capture());
        assertEquals(7L, captor.getValue().taskId());
        assertSame(result, captor.getValue().result());
    }

    @Test
    void handle_invalidPath_publishesFailedEventWithoutAbsolutePath() {
        when(scanPreviews.scan(any(Path.class)))
                .thenThrow(new IllegalArgumentException("父目录不存在"));

        handler.handle(request(9L, "D:/secret/root"), channel, 1L);

        ArgumentCaptor<DirectoryScanFailedEvent> captor = ArgumentCaptor.forClass(DirectoryScanFailedEvent.class);
        verify(rabbitTemplate).convertAndSend(eq(MqExchanges.SCAN), eq(MqRoutingKeys.SCAN_FAILED), captor.capture());
        assertEquals(9L, captor.getValue().taskId());
        assertEquals("父目录不存在", captor.getValue().errorMessage());
        assertFalse(captor.getValue().errorMessage().contains("D:/secret"),
                "失败事件消息不得携带宿主机绝对路径");
    }

    @Test
    void handle_mapsHostPathBeforeScanning() {
        when(workerConfig.mapHostPathToContainer("D:/manga/comics"))
                .thenReturn("/storage/comics");
        when(scanPreviews.scan(Path.of("/storage/comics")))
                .thenReturn(new ScanResultDTO("/storage/comics", 0, List.of()));

        handler.handle(request(10L, "D:/manga/comics"), channel, 1L);

        verify(scanPreviews).scan(Path.of("/storage/comics"));
    }
}
