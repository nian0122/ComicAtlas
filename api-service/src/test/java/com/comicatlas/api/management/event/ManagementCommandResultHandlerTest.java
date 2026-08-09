package com.comicatlas.api.management.event;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.comicatlas.api.comic.cache.CatalogCacheInvalidator;
import com.comicatlas.api.comic.entity.Comic;
import com.comicatlas.api.comic.mapper.CatalogMapper;
import com.comicatlas.api.comic.mapper.ChapterMapper;
import com.comicatlas.api.comic.mapper.ComicMapper;
import com.comicatlas.api.comic.mapper.ComicTagMapper;
import com.comicatlas.api.comic.mapper.MediaMapper;
import com.comicatlas.api.common.enums.ComicStatus;
import com.comicatlas.api.common.enums.ManagementTaskStatus;
import com.comicatlas.api.common.enums.TaskType;
import com.comicatlas.api.common.exception.BusinessException;
import com.comicatlas.api.common.storage.ApiStorageProperties;
import com.comicatlas.api.common.storage.ApiStorageRoot;
import com.comicatlas.api.management.dto.ManagementTaskItemResponse;
import com.comicatlas.api.management.entity.ManagementTaskItem;
import com.comicatlas.api.management.mapper.ManagementTaskItemMapper;
import com.comicatlas.api.management.service.ManagementTaskService;
import com.comicatlas.api.management.trash.TrashManifestService;
import com.comicatlas.api.outbox.service.InboxService;
import com.comicatlas.api.outbox.service.OutboxService;
import com.comicatlas.api.reader.mapper.ReadingHistoryMapper;
import com.comicatlas.api.storage.service.MediaMetadataSyncService;
import com.comicatlas.api.storage.service.MetadataRefreshService;
import com.comicatlas.api.upload.UploadSessionService;
import com.comicatlas.api.upload.mapper.UploadSessionMapper;
import com.comicatlas.common.constant.MqExchanges;
import com.comicatlas.common.constant.MqRoutingKeys;
import com.comicatlas.common.dto.MetadataRefreshSnapshotDTO;
import com.comicatlas.common.event.ManagementCommandFailedEvent;
import com.comicatlas.common.event.MetadataRefreshEvent;
import com.comicatlas.common.event.MetadataRefreshScanCompletedEvent;
import com.comicatlas.common.mq.MqConsumerSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.rabbitmq.client.Channel;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * ManagementCommandResultHandler 元数据扫盘刷新完成事件专用流程单元测试（TDD Todo 5）。
 * <p>
 * 覆盖：happy（item SUCCEEDED + comic READY + Inbox + Outbox + 快照清理）、幂等前置检查、
 * 同 attempt 不同 eventId 的 CAS 败者跳过、业务失败（SHA 篡改/revision 漂移）走失败短事务、
 * 基础设施失败（Outbox 异常）reject/DLQ 不伪造成功、failed 事件释放 comic。
 * <p>
 * 事务边界：快照读取/校验在事务外（mock 验证顺序由生产代码保证），
 * 所有 DB 写入在一个短事务内（mock TransactionTemplate 直接执行回调）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ManagementCommandResultHandlerTest — 元数据刷新完成专用流程")
class ManagementCommandResultHandlerTest {

    @Mock private ManagementTaskService managementTaskService;
    @Mock private InboxService inboxService;
    @Mock private MediaMapper mediaMapper;
    @Mock private ChapterMapper chapterMapper;
    @Mock private ComicMapper comicMapper;
    @Mock private CatalogMapper catalogMapper;
    @Mock private ComicTagMapper comicTagMapper;
    @Mock private ReadingHistoryMapper readingHistoryMapper;
    @Mock private TrashManifestService trashManifestService;
    @Mock private CatalogCacheInvalidator catalogCacheInvalidator;
    @Mock private TransactionTemplate transactionTemplate;
    @Spy private ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    @Mock private UploadSessionMapper uploadSessionMapper;
    @Mock private UploadSessionService uploadSessionService;
    @Mock private MediaMetadataSyncService mediaMetadataSyncService;
    @Spy private MqConsumerSupport mqConsumerSupport = new MqConsumerSupport();
    @Mock private ManagementTaskItemMapper managementTaskItemMapper;
    @Mock private MetadataRefreshService metadataRefreshService;
    @Mock private OutboxService outboxService;
    @Mock private ApiStorageProperties apiStorageProperties;

    @InjectMocks private ManagementCommandResultHandler handler;

    @Mock private Channel channel;

    @TempDir Path tempDir;

    private Path staging;
    private Path snapshotDir;

    @BeforeEach
    void setUp() throws Exception {
        // 单元测试无 Spring 上下文：注册实体 TableInfo 以支持 LambdaUpdateWrapper 解析
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Comic.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), ManagementTaskItem.class);

        staging = tempDir.resolve("staging");
        Files.createDirectories(staging);
        ApiStorageRoot stagingRoot = new ApiStorageRoot();
        stagingRoot.setPath(staging);
        lenient().when(apiStorageProperties.root("STAGING")).thenReturn(stagingRoot);

        // 让 TransactionTemplate 直接执行回调（等价于短事务提交）
        lenient().doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            TransactionCallback<Boolean> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        }).when(transactionTemplate).execute(any(TransactionCallback.class));

        lenient().doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<TransactionStatus> consumer = invocation.getArgument(0);
            consumer.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    // ======================== 测试数据 ========================

    private ManagementTaskItem runningItem() {
        ManagementTaskItem item = new ManagementTaskItem();
        item.setId(100L);
        item.setTaskId(10L);
        item.setTargetType("COMIC");
        item.setTargetId(1L);
        item.setOperationType(TaskType.METADATA_REFRESH);
        item.setStatus(ManagementTaskStatus.RUNNING);
        item.setAttempt(1);
        return item;
    }

    private Comic refreshingComic() {
        Comic comic = new Comic();
        comic.setId(1L);
        comic.setStatus(ComicStatus.REFRESHING);
        return comic;
    }

    private MetadataRefreshSnapshotDTO snapshot() {
        return new MetadataRefreshSnapshotDTO(1, 1L, Instant.parse("2026-08-09T00:00:00Z"), "rev", List.of());
    }

    private MetadataRefreshScanCompletedEvent completedEvent() throws Exception {
        snapshotDir = staging.resolve("metadata-refresh/10/100/1");
        Path snapshotFile = snapshotDir.resolve("snapshot.json");
        Files.createDirectories(snapshotDir);
        Files.writeString(snapshotFile, "{}");
        return new MetadataRefreshScanCompletedEvent(
                UUID.randomUUID(), Instant.now(), 1,
                10L, 100L, 1, "METADATA_REFRESH", "COMIC", 1L,
                "metadata-refresh/10/100/1/snapshot.json", "deadbeef", 2L, 1);
    }

    // ======================== happy ========================

    @Test
    @DisplayName("happy：completed → item SUCCEEDED、comic READY、Inbox、Outbox 入箱 MetadataRefreshEvent、快照清理、ACK")
    void happy_completed_appliesAndCleansSnapshot() throws Exception {
        when(managementTaskItemMapper.selectById(100L)).thenReturn(runningItem());
        when(metadataRefreshService.loadAndValidate(any())).thenReturn(snapshot());
        when(comicMapper.selectByIdForUpdate(1L)).thenReturn(refreshingComic());
        when(comicMapper.update(isNull(), any())).thenReturn(1);
        when(managementTaskItemMapper.update(isNull(), any())).thenReturn(1);

        MetadataRefreshScanCompletedEvent ev = completedEvent();
        handler.handleResult(ev, channel, 1L);

        // item CAS → SUCCEEDED；comic CAS → READY
        verify(managementTaskItemMapper).update(isNull(), any(LambdaUpdateWrapper.class));
        verify(comicMapper).update(isNull(), any(LambdaUpdateWrapper.class));
        // 差异合并 + Inbox + Outbox 入箱 MetadataRefreshEvent + 任务聚合 + 缓存失效
        verify(metadataRefreshService).applyValidatedSnapshot(any());
        verify(inboxService).markProcessed(eq(ev.eventId().toString()), anyString(), eq(10L), eq(100L), eq(1));
        verify(outboxService).enqueue(any(MetadataRefreshEvent.class), eq(MqExchanges.EXPORT),
                eq(MqRoutingKeys.METADATA_REFRESH_REQUESTED), eq(10L), eq(100L), eq(1));
        verify(managementTaskService).reaggregateTask(10L);
        verify(catalogCacheInvalidator).evict(1L);
        // 提交后清理快照目录
        assertThat(Files.exists(snapshotDir)).isFalse();
        // ACK
        verify(channel).basicAck(1L, false);
    }

    // ======================== 幂等前置检查（事务外，直接 ACK） ========================

    @Test
    @DisplayName("item 不存在（幽灵事件）：不读取快照，ACK")
    void ghostItem_acked() throws Exception {
        when(managementTaskItemMapper.selectById(100L)).thenReturn(null);

        handler.handleResult(completedEvent(), channel, 1L);

        verifyNoInteractions(metadataRefreshService, outboxService);
        verify(channel).basicAck(1L, false);
    }

    @Test
    @DisplayName("item 已终态：不二次 apply，ACK")
    void terminalItem_acked() throws Exception {
        ManagementTaskItem item = runningItem();
        item.setStatus(ManagementTaskStatus.SUCCEEDED);
        when(managementTaskItemMapper.selectById(100L)).thenReturn(item);

        handler.handleResult(completedEvent(), channel, 1L);

        verifyNoInteractions(metadataRefreshService, outboxService);
        verify(channel).basicAck(1L, false);
    }

    @Test
    @DisplayName("不同 attempt：忽略旧 attempt 完成结果，ACK")
    void staleAttempt_acked() throws Exception {
        ManagementTaskItem item = runningItem();
        item.setAttempt(2);
        when(managementTaskItemMapper.selectById(100L)).thenReturn(item);

        handler.handleResult(completedEvent(), channel, 1L);

        verifyNoInteractions(metadataRefreshService, outboxService);
        verify(channel).basicAck(1L, false);
    }

    @Test
    @DisplayName("operationType 非 METADATA_REFRESH：防御性忽略，ACK")
    void wrongOperation_acked() throws Exception {
        ManagementTaskItem item = runningItem();
        item.setOperationType(TaskType.LQ_GENERATE);
        when(managementTaskItemMapper.selectById(100L)).thenReturn(item);

        handler.handleResult(completedEvent(), channel, 1L);

        verifyNoInteractions(metadataRefreshService, outboxService);
        verify(channel).basicAck(1L, false);
    }

    // ======================== 同 attempt 不同 eventId：CAS 败者 ========================

    @Test
    @DisplayName("item CAS 0 行（已被其他 eventId 处理）：不 apply、不写 Outbox，ACK，快照保留")
    void casLoser_skipsApplyAndKeepsSnapshot() throws Exception {
        when(managementTaskItemMapper.selectById(100L)).thenReturn(runningItem());
        when(metadataRefreshService.loadAndValidate(any())).thenReturn(snapshot());
        when(comicMapper.selectByIdForUpdate(1L)).thenReturn(refreshingComic());
        when(comicMapper.update(isNull(), any())).thenReturn(1);
        when(managementTaskItemMapper.update(isNull(), any())).thenReturn(0);

        MetadataRefreshScanCompletedEvent ev = completedEvent();
        handler.handleResult(ev, channel, 1L);

        // 败者：不做差异合并、不重导出、不聚合
        verify(metadataRefreshService, never()).applyValidatedSnapshot(any());
        verify(outboxService, never()).enqueue(any(), anyString(), anyString());
        verify(managementTaskService, never()).reaggregateTask(any());
        // 仍记录 Inbox（eventId 幂等键）
        verify(inboxService).markProcessed(eq(ev.eventId().toString()), anyString(), eq(10L), eq(100L), eq(1));
        // 快照保留（胜者负责清理）
        assertThat(Files.exists(snapshotDir)).isTrue();
        verify(channel).basicAck(1L, false);
    }

    // ======================== 业务失败：失败短事务 + ACK + 快照保留 ========================

    @Test
    @DisplayName("loadAndValidate 业务失败（SHA 篡改）：item/task FAILED、comic READY、Inbox、ACK、快照保留")
    void loadValidateBusinessFailure_failsItemAndReleasesComic() throws Exception {
        when(managementTaskItemMapper.selectById(100L)).thenReturn(runningItem());
        when(metadataRefreshService.loadAndValidate(any()))
                .thenThrow(new BusinessException("快照 SHA-256 与事件声明不一致"));
        when(managementTaskItemMapper.update(isNull(), any())).thenReturn(1);
        when(comicMapper.update(isNull(), any())).thenReturn(1);

        MetadataRefreshScanCompletedEvent ev = completedEvent();
        handler.handleResult(ev, channel, 1L);

        // 失败短事务：item FAILED + comic READY + 任务聚合 + Inbox
        verify(managementTaskItemMapper).update(isNull(), any(LambdaUpdateWrapper.class));
        verify(comicMapper).update(isNull(), any(LambdaUpdateWrapper.class));
        verify(managementTaskService).reaggregateTask(10L);
        verify(inboxService).markProcessed(eq(ev.eventId().toString()), anyString(), eq(10L), eq(100L), eq(1));
        // 不 apply 快照、不重导出
        verify(metadataRefreshService, never()).applyValidatedSnapshot(any());
        verify(outboxService, never()).enqueue(any(), anyString(), anyString());
        // 快照保留（供重试/排查）
        assertThat(Files.exists(snapshotDir)).isTrue();
        verify(channel).basicAck(1L, false);
    }

    @Test
    @DisplayName("apply 复核 databaseRevision 漂移：整事务回滚后走失败短事务，ACK，快照保留")
    void applyRevisionDrift_failsItemAndReleasesComic() throws Exception {
        when(managementTaskItemMapper.selectById(100L)).thenReturn(runningItem());
        when(metadataRefreshService.loadAndValidate(any())).thenReturn(snapshot());
        when(comicMapper.selectByIdForUpdate(1L)).thenReturn(refreshingComic());
        when(comicMapper.update(isNull(), any())).thenReturn(1);
        when(managementTaskItemMapper.update(isNull(), any())).thenReturn(1);
        when(metadataRefreshService.applyValidatedSnapshot(any()))
                .thenThrow(new BusinessException("快照结构摘要与自带 databaseRevision 不一致"));

        MetadataRefreshScanCompletedEvent ev = completedEvent();
        handler.handleResult(ev, channel, 1L);

        // 成功短事务内 apply 已调用（抛出后整体回滚），随后失败短事务执行
        verify(metadataRefreshService).applyValidatedSnapshot(any());
        verify(managementTaskItemMapper, times(2)).update(isNull(), any(LambdaUpdateWrapper.class));
        verify(comicMapper, times(2)).update(isNull(), any(LambdaUpdateWrapper.class));
        verify(managementTaskService).reaggregateTask(10L);
        verify(inboxService).markProcessed(eq(ev.eventId().toString()), anyString(), eq(10L), eq(100L), eq(1));
        verify(outboxService, never()).enqueue(any(), anyString(), anyString());
        assertThat(Files.exists(snapshotDir)).isTrue();
        verify(channel).basicAck(1L, false);
    }

    // ======================== 基础设施失败：reject/DLQ，不伪造成功 ========================

    @Test
    @DisplayName("Outbox 入箱抛异常：异常传播 → reject/DLQ，不走失败短事务")
    void infraFailure_outboxThrows_rejectsToDlq() throws Exception {
        when(managementTaskItemMapper.selectById(100L)).thenReturn(runningItem());
        when(metadataRefreshService.loadAndValidate(any())).thenReturn(snapshot());
        when(comicMapper.selectByIdForUpdate(1L)).thenReturn(refreshingComic());
        when(comicMapper.update(isNull(), any())).thenReturn(1);
        when(managementTaskItemMapper.update(isNull(), any())).thenReturn(1);
        when(metadataRefreshService.applyValidatedSnapshot(any())).thenReturn(null);
        doThrow(new RuntimeException("Outbox 序列化失败"))
                .when(outboxService).enqueue(any(MetadataRefreshEvent.class), anyString(), anyString(), any(), any(), anyInt());

        MetadataRefreshScanCompletedEvent ev = completedEvent();
        handler.handleResult(ev, channel, 1L);

        // 基础设施故障 → reject（进 DLQ），不 ACK、不伪造成功
        verify(channel).basicReject(1L, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        // 任务聚合未执行（事务未提交）
        verify(managementTaskService, never()).reaggregateTask(any());
        // 快照保留（未到提交后清理）
        assertThat(Files.exists(snapshotDir)).isTrue();
    }

    @Test
    @DisplayName("快照文件不可用（IOException 被包装为业务异常）：视为基础设施故障 → reject/DLQ")
    void infraFailure_snapshotIo_fallsToDlq() throws Exception {
        when(managementTaskItemMapper.selectById(100L)).thenReturn(runningItem());
        when(metadataRefreshService.loadAndValidate(any()))
                .thenThrow(new BusinessException("快照读取失败", new IOException("文件不存在")));

        handler.handleResult(completedEvent(), channel, 1L);

        verify(channel).basicReject(1L, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        // 失败短事务未执行（不标记 FAILED，等 DLQ 重放）
        verify(managementTaskItemMapper, never()).update(isNull(), any(LambdaUpdateWrapper.class));
    }

    @Test
    @DisplayName("快照文件缺失（Files.isRegularFile 返回 false，无 IO cause）：视为基础设施故障 → reject/DLQ")
    void infraFailure_snapshotNotRegularFile_fallsToDlq() throws Exception {
        when(managementTaskItemMapper.selectById(100L)).thenReturn(runningItem());
        when(metadataRefreshService.loadAndValidate(any()))
                .thenThrow(new BusinessException("快照必须是常规文件且禁止符号链接: snapshot.json"));

        handler.handleResult(completedEvent(), channel, 1L);

        verify(channel).basicReject(1L, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        verify(managementTaskItemMapper, never()).update(isNull(), any(LambdaUpdateWrapper.class));
    }

    // ======================== failed 事件释放 comic ========================

    @Test
    @DisplayName("failed 事件：METADATA_REFRESH 释放 comic REFRESHING → READY")
    void failedEvent_releasesComicRefreshing() throws Exception {
        when(inboxService.isProcessed(anyString(), anyString())).thenReturn(false);
        ManagementTaskItemResponse resp = new ManagementTaskItemResponse();
        resp.setStatus(ManagementTaskStatus.FAILED);
        when(managementTaskService.updateItemStatus(eq(100L), eq(ManagementTaskStatus.FAILED),
                anyString(), isNull(), isNull(), eq(1))).thenReturn(resp);

        var ev = new ManagementCommandFailedEvent(UUID.randomUUID(), Instant.now(), 1,
                10L, 100L, 1, "METADATA_REFRESH", "COMIC", 1L, "Worker 失败");
        handler.handleResult(ev, channel, 1L);

        verify(comicMapper).update(isNull(), any(LambdaUpdateWrapper.class));
        verify(channel).basicAck(1L, false);
    }
}
