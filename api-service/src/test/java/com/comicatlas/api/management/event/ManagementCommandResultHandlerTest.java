package com.comicatlas.api.management.event;

import com.comicatlas.api.management.dto.ManagementTaskItemResponse;
import com.comicatlas.api.management.service.ManagementTaskService;
import com.comicatlas.api.management.trash.TrashLifecycleCompletionService;
import com.comicatlas.api.outbox.service.InboxService;
import com.comicatlas.api.storage.service.ComicStatsService;
import com.comicatlas.api.media.service.MediaOperationCompletionService;
import com.comicatlas.api.metadata.service.MetadataRefreshCompletionService;
import com.comicatlas.api.upload.UploadCompletionService;
import com.comicatlas.common.event.ManagementCommandCompletedEvent;
import com.comicatlas.common.event.ManagementCommandFailedEvent;
import com.comicatlas.common.event.ManagementCommandProgressEvent;
import com.comicatlas.common.event.MediaUploadCompletedEvent;
import com.comicatlas.common.mq.MqConsumerSupport;
import com.comicatlas.api.common.enums.ManagementTaskStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ManagementCommandResultHandler 路由分发单元测试。
 * <p>
 * 覆盖：completed/failed/progress/upload 结果事件按操作类型分发到
 * 各领域 completion service（存储操作 / 回收生命周期 / 上传 / 元数据刷新），
 * item 状态流转（attempt 条件更新）与 Inbox 幂等由事务模板 + MqConsumerSupport 支撑。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ManagementCommandResultHandlerTest — 结果事件路由分发")
class ManagementCommandResultHandlerTest {

    @Mock private ManagementTaskService managementTaskService;
    @Mock private InboxService inboxService;
    @Spy private MqConsumerSupport mqConsumerSupport = new MqConsumerSupport();
    @Mock private TransactionTemplate transactionTemplate;
    @Spy private ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    @Mock private MediaOperationCompletionService mediaOperationCompletionService;
    @Mock private TrashLifecycleCompletionService trashLifecycleCompletionService;
    @Mock private UploadCompletionService uploadCompletionService;
    @Mock private MetadataRefreshCompletionService metadataRefreshCompletionService;
    @Mock private ComicStatsService comicStatsService;

    @InjectMocks private ManagementCommandResultHandler handler;

    @Mock private Channel channel;

    @BeforeEach
    void setUp() {
        // 事务模板直接执行回调；Inbox 未处理（幂等放行）
        lenient().doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<TransactionStatus> consumer = invocation.getArgument(0);
            consumer.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
        lenient().when(inboxService.isProcessed(anyString(), anyString())).thenReturn(false);
    }

    private ManagementTaskItemResponse succeeded() {
        ManagementTaskItemResponse resp = new ManagementTaskItemResponse();
        resp.setStatus(ManagementTaskStatus.SUCCEEDED);
        return resp;
    }

    private ManagementTaskItemResponse failed() {
        ManagementTaskItemResponse resp = new ManagementTaskItemResponse();
        resp.setStatus(ManagementTaskStatus.FAILED);
        return resp;
    }

    @Test
    @DisplayName("completed LQ_GENERATE（CHAPTER）：分发到 MediaOperationCompletionService 并携带 lqSizes")
    void completed_lqGenerate_delegatesToMediaOperation() {
        when(managementTaskService.updateItemStatus(eq(1L), eq(ManagementTaskStatus.SUCCEEDED),
                isNull(), isNull(), isNull(), eq(1))).thenReturn(succeeded());
        var ev = new ManagementCommandCompletedEvent(UUID.randomUUID(), Instant.now(), 1,
                10L, 1L, 1, "LQ_GENERATE", "CHAPTER", 42L, null);

        handler.handleResult(ev, channel, 1L);

        verify(mediaOperationCompletionService).applyLqCompleted(eq(42L), isNull());
    }

    @Test
    @DisplayName("completed HQ_DELETE（COMIC）：按章节展开后逐章分发")
    void completed_hqDeleteComic_expandsChapters() {
        when(comicStatsService.chapterIdsOf(9L)).thenReturn(List.of(100L, 200L));
        when(managementTaskService.updateItemStatus(eq(1L), eq(ManagementTaskStatus.SUCCEEDED),
                isNull(), isNull(), isNull(), eq(1))).thenReturn(succeeded());
        var ev = new ManagementCommandCompletedEvent(UUID.randomUUID(), Instant.now(), 1,
                10L, 1L, 1, "HQ_DELETE", "COMIC", 9L, null);

        handler.handleResult(ev, channel, 1L);

        verify(mediaOperationCompletionService).applyHqDeleteCompleted(100L);
        verify(mediaOperationCompletionService).applyHqDeleteCompleted(200L);
    }

    @Test
    @DisplayName("completed TRANSCODE（MEDIA）：分发转码落库并触发整本同步检查")
    void completed_transcode_delegatesToMediaOperation() {
        when(managementTaskService.updateItemStatus(eq(1L), eq(ManagementTaskStatus.SUCCEEDED),
                isNull(), isNull(), isNull(), eq(1))).thenReturn(succeeded());
        var ev = new ManagementCommandCompletedEvent(UUID.randomUUID(), Instant.now(), 1,
                10L, 1L, 1, "TRANSCODE", "MEDIA", 55L, null);

        handler.handleResult(ev, channel, 1L);

        verify(mediaOperationCompletionService).applyTranscodeCompleted(ev, 55L);
        verify(mediaOperationCompletionService).maybeNotifyTranscodeTaskCompleted(ev);
    }

    @Test
    @DisplayName("completed MEDIA_TRASH：分发到 TrashLifecycleCompletionService")
    void completed_mediaTrash_delegatesToTrashLifecycle() {
        when(managementTaskService.updateItemStatus(eq(1L), eq(ManagementTaskStatus.SUCCEEDED),
                isNull(), isNull(), isNull(), eq(1))).thenReturn(succeeded());
        var ev = new ManagementCommandCompletedEvent(UUID.randomUUID(), Instant.now(), 1,
                10L, 1L, 1, "MEDIA_TRASH", "MEDIA", 77L, null);

        handler.handleResult(ev, channel, 1L);

        verify(trashLifecycleCompletionService).applyMediaTrashCompleted(ev);
    }

    @Test
    @DisplayName("failed METADATA_REFRESH：释放 comic REFRESHING 锁")
    void failed_metadataRefresh_releasesComic() {
        when(managementTaskService.updateItemStatus(eq(1L), eq(ManagementTaskStatus.FAILED),
                anyString(), isNull(), isNull(), eq(1))).thenReturn(failed());
        var ev = new ManagementCommandFailedEvent(UUID.randomUUID(), Instant.now(), 1,
                10L, 1L, 1, "METADATA_REFRESH", "COMIC", 9L, "Worker 失败");

        handler.handleResult(ev, channel, 1L);

        verify(metadataRefreshCompletionService).releaseComicRefreshing(9L);
    }

    @Test
    @DisplayName("failed MEDIA_UPLOAD：会话置 FAILED")
    void failed_upload_revertsSession() {
        when(managementTaskService.updateItemStatus(eq(1L), eq(ManagementTaskStatus.FAILED),
                anyString(), isNull(), isNull(), eq(1))).thenReturn(failed());
        var ev = new ManagementCommandFailedEvent(UUID.randomUUID(), Instant.now(), 1,
                10L, 1L, 1, "MEDIA_UPLOAD", "CHAPTER", 42L, "磁盘不足");

        handler.handleResult(ev, channel, 1L);

        verify(uploadCompletionService).revertUploadFailed(42L);
    }

    @Test
    @DisplayName("progress LQ_GENERATE：media 置 GENERATING")
    void progress_lqGenerate_transitionsMedia() {
        when(managementTaskService.updateItemProgress(eq(1L), eq(1), eq(50), eq("生成中"))).thenReturn(true);
        var ev = new ManagementCommandProgressEvent(UUID.randomUUID(), Instant.now(), 1,
                10L, 1L, 1, "LQ_GENERATE", "CHAPTER", 42L, 50, "生成中");

        handler.handleResult(ev, channel, 1L);

        verify(mediaOperationCompletionService).transitionLqGenerating(42L);
    }

    @Test
    @DisplayName("upload completed：分发到 UploadCompletionService")
    void uploadCompleted_delegatesToUpload() {
        when(managementTaskService.updateItemStatus(eq(1L), eq(ManagementTaskStatus.SUCCEEDED),
                isNull(), isNull(), isNull(), eq(1))).thenReturn(succeeded());
        var ev = new MediaUploadCompletedEvent(UUID.randomUUID(), Instant.now(), 1,
                10L, 1L, 1, "MEDIA_UPLOAD", "CHAPTER", 42L, List.of());

        handler.handleResult(ev, channel, 1L);

        verify(uploadCompletionService).applyUploadCompletedBusiness(ev);
    }
}
