package com.comicatlas.worker.event;

import com.comicatlas.common.event.ManagementCommandRequestedEvent;
import com.comicatlas.common.mq.MqConsumerSupport;
import com.comicatlas.worker.command.HqDeleteCommandHandler;
import com.comicatlas.worker.command.LqCommandHandler;
import com.comicatlas.worker.command.MediaUploadCommandHandler;
import com.comicatlas.worker.command.MetadataRefreshCommandHandler;
import com.comicatlas.worker.command.PurgeCommandHandler;
import com.comicatlas.worker.command.RestoreCommandHandler;
import com.comicatlas.worker.command.TranscodeCommandHandler;
import com.comicatlas.worker.command.TrashCommandHandler;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 管理命令分发器单元测试：METADATA_REFRESH（COMIC 级）路由到元数据扫盘刷新
 * 处理器执行扫盘，不再 fail-closed 直接回 FAILED；其他命令处理器不被误触。
 */
class ManagementCommandDispatcherTest {

    private final LqCommandHandler lqCommandHandler = mock(LqCommandHandler.class);
    private final HqDeleteCommandHandler hqDeleteCommandHandler = mock(HqDeleteCommandHandler.class);
    private final TranscodeCommandHandler transcodeCommandHandler = mock(TranscodeCommandHandler.class);
    private final TrashCommandHandler trashCommandHandler = mock(TrashCommandHandler.class);
    private final RestoreCommandHandler restoreCommandHandler = mock(RestoreCommandHandler.class);
    private final PurgeCommandHandler purgeCommandHandler = mock(PurgeCommandHandler.class);
    private final MediaUploadCommandHandler mediaUploadCommandHandler = mock(MediaUploadCommandHandler.class);
    private final MetadataRefreshCommandHandler metadataRefreshCommandHandler = mock(MetadataRefreshCommandHandler.class);
    private final ManagementCommandPublisher publisher = mock(ManagementCommandPublisher.class);
    private final ManagementCommandDispatcher dispatcher = new ManagementCommandDispatcher(
            lqCommandHandler, hqDeleteCommandHandler, transcodeCommandHandler,
            trashCommandHandler, restoreCommandHandler, purgeCommandHandler,
            mediaUploadCommandHandler, metadataRefreshCommandHandler, publisher, new MqConsumerSupport());
    private final Channel channel = mock(Channel.class);

    @Test
    void metadataRefresh命令路由到扫盘处理器且不调用其他命令处理器() throws Exception {
        ManagementCommandRequestedEvent cmd = new ManagementCommandRequestedEvent(
                UUID.randomUUID(), Instant.now(), 1, 1L, 1L, 1,
                "METADATA_REFRESH", "COMIC", 42L);

        dispatcher.handle(cmd, channel, 1L);

        verify(metadataRefreshCommandHandler).refresh(cmd);
        verify(publisher, never()).failed(eq(cmd), anyString());
        verifyNoInteractions(lqCommandHandler, hqDeleteCommandHandler, transcodeCommandHandler,
                trashCommandHandler, restoreCommandHandler, purgeCommandHandler,
                mediaUploadCommandHandler);
        // 扫盘由 handler 内部发布 completed/failed：命令正常 ack，不进入 DLQ
        verify(channel).basicAck(1L, false);
    }
}
