package com.comicatlas.worker.event;

import com.comicatlas.common.constant.MetadataRefreshConstants;
import com.comicatlas.common.event.ManagementCommandRequestedEvent;
import com.comicatlas.common.mq.MqConsumerSupport;
import com.comicatlas.worker.command.HqDeleteCommandHandler;
import com.comicatlas.worker.command.LqCommandHandler;
import com.comicatlas.worker.command.MediaUploadCommandHandler;
import com.comicatlas.worker.command.PurgeCommandHandler;
import com.comicatlas.worker.command.RestoreCommandHandler;
import com.comicatlas.worker.command.TranscodeCommandHandler;
import com.comicatlas.worker.command.TrashCommandHandler;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 管理命令分发器单元测试：已停用的 METADATA_REFRESH 命令直接回 FAILED，
 * 不调用任何命令处理器，且正常 ack 不进 DLQ。
 */
class ManagementCommandDispatcherTest {

    private final LqCommandHandler lqCommandHandler = mock(LqCommandHandler.class);
    private final HqDeleteCommandHandler hqDeleteCommandHandler = mock(HqDeleteCommandHandler.class);
    private final TranscodeCommandHandler transcodeCommandHandler = mock(TranscodeCommandHandler.class);
    private final TrashCommandHandler trashCommandHandler = mock(TrashCommandHandler.class);
    private final RestoreCommandHandler restoreCommandHandler = mock(RestoreCommandHandler.class);
    private final PurgeCommandHandler purgeCommandHandler = mock(PurgeCommandHandler.class);
    private final MediaUploadCommandHandler mediaUploadCommandHandler = mock(MediaUploadCommandHandler.class);
    private final ManagementCommandPublisher publisher = mock(ManagementCommandPublisher.class);
    private final ManagementCommandDispatcher dispatcher = new ManagementCommandDispatcher(
            lqCommandHandler, hqDeleteCommandHandler, transcodeCommandHandler,
            trashCommandHandler, restoreCommandHandler, purgeCommandHandler,
            mediaUploadCommandHandler, publisher, new MqConsumerSupport());
    private final Channel channel = mock(Channel.class);

    @Test
    void metadataRefresh命令直接回FAILED且不调用任何命令处理器() throws Exception {
        ManagementCommandRequestedEvent cmd = new ManagementCommandRequestedEvent(
                UUID.randomUUID(), Instant.now(), 1, 1L, 1L, 1,
                "METADATA_REFRESH", "COMIC", 42L);

        dispatcher.handle(cmd, channel, 1L);

        verify(publisher).failed(cmd, MetadataRefreshConstants.METADATA_REFRESH_DISABLED_REASON);
        verifyNoInteractions(lqCommandHandler, hqDeleteCommandHandler, transcodeCommandHandler,
                trashCommandHandler, restoreCommandHandler, purgeCommandHandler,
                mediaUploadCommandHandler);
        // FAILED 事件即业务结果：命令正常 ack，不进入 DLQ
        verify(channel).basicAck(1L, false);
    }
}
