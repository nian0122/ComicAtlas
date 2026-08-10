package com.comicatlas.api.management.event;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.comicatlas.api.comic.cache.CatalogCacheInvalidator;
import com.comicatlas.api.comic.entity.Chapter;
import com.comicatlas.api.comic.entity.Comic;
import com.comicatlas.api.comic.entity.Media;
import com.comicatlas.api.comic.mapper.CatalogMapper;
import com.comicatlas.api.comic.mapper.ChapterMapper;
import com.comicatlas.api.comic.mapper.ComicMapper;
import com.comicatlas.api.comic.mapper.ComicTagMapper;
import com.comicatlas.api.comic.mapper.MediaMapper;
import com.comicatlas.api.common.enums.ComicStatus;
import com.comicatlas.api.common.enums.HqStatus;
import com.comicatlas.api.common.enums.LqStatus;
import com.comicatlas.api.common.enums.ManagementTaskStatus;
import com.comicatlas.api.common.enums.MediaLifecycleStatus;
import com.comicatlas.api.common.enums.TaskType;
import com.comicatlas.api.common.enums.TranscodeStatus;
import com.comicatlas.api.common.exception.BusinessException;
import com.comicatlas.api.common.exception.SnapshotUnavailableException;
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
import com.comicatlas.common.event.ManagementCommandCompletedEvent;
import com.comicatlas.common.event.ManagementCommandFailedEvent;
import com.comicatlas.common.event.ManagementCommandProgressEvent;
import com.comicatlas.common.event.MetadataRefreshEvent;
import com.comicatlas.common.event.MetadataRefreshScanCompletedEvent;
import com.comicatlas.common.event.payload.LqGenerationResult;
import com.comicatlas.common.event.payload.LqMediaResult;
import com.comicatlas.common.event.payload.TranscodeMediaInfo;
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
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collection;
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
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Media.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Chapter.class);

        staging = tempDir.resolve("staging");
        Files.createDirectories(staging);
        ApiStorageRoot stagingRoot = new ApiStorageRoot();
        stagingRoot.setPath(staging);
        lenient().when(apiStorageProperties.root("STAGING")).thenReturn(stagingRoot);

        // LQ 根：供 LQ 完成结果路径 containment 校验（resolve 后必须位于 LQ 根内）
        ApiStorageRoot lqStorageRoot = new ApiStorageRoot();
        lqStorageRoot.setPath(tempDir.resolve("lq"));
        lenient().when(apiStorageProperties.root("LQ")).thenReturn(lqStorageRoot);

        // HQ 根：供转码完成产物路径 containment 校验（resolve 后必须位于 HQ 根内）
        ApiStorageRoot hqStorageRoot = new ApiStorageRoot();
        hqStorageRoot.setPath(tempDir.resolve("hq"));
        lenient().when(apiStorageProperties.root("HQ")).thenReturn(hqStorageRoot);

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
                .thenThrow(new SnapshotUnavailableException("快照读取失败", new IOException("文件不存在")));

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
                .thenThrow(new SnapshotUnavailableException("快照产物不可用（非常规文件或符号链接）: snapshot.json"));

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

    // ======================== LQ 完成逐媒体落库（Todo 6） ========================

    private ManagementTaskItem lqItem() {
        ManagementTaskItem item = new ManagementTaskItem();
        item.setId(200L);
        item.setTaskId(20L);
        item.setTargetType("CHAPTER");
        item.setTargetId(5L);
        item.setOperationType(TaskType.LQ_GENERATE);
        item.setStatus(ManagementTaskStatus.RUNNING);
        item.setAttempt(1);
        return item;
    }

    private Media lqMedia(Long id, Long chapterId, int page, String hqPath, LqStatus lqStatus) {
        Media media = new Media();
        media.setId(id);
        media.setChapterId(chapterId);
        media.setPageNumber(page);
        media.setMediaType("IMAGE");
        media.setHqRoot("HQ");
        media.setHqPath(hqPath);
        media.setHqStatus(HqStatus.READY);
        media.setLqStatus(lqStatus);
        media.setStatus(MediaLifecycleStatus.READY);
        media.setVersion(1);
        return media;
    }

    private ManagementCommandCompletedEvent lqCompletedEvent(LqGenerationResult result) {
        return new ManagementCommandCompletedEvent(UUID.randomUUID(), Instant.now(), 1,
                20L, 200L, 1, "LQ_GENERATE", "CHAPTER", 5L, null, result);
    }

    private LqMediaResult lqReady(Long mediaId, int page, String hqPath, String lqPath, long size) {
        return new LqMediaResult(mediaId, page, hqPath, LqMediaResult.STATUS_READY,
                "LQ", lqPath, size, null, null);
    }

    private LqMediaResult lqFailed(Long mediaId, int page, String hqPath) {
        return new LqMediaResult(mediaId, page, hqPath, LqMediaResult.STATUS_FAILED,
                null, null, 0L, "LQ_OPTIMIZE_FAILED", "优化失败");
    }

    /** 提取 LambdaUpdateWrapper 中 set 的字段值集合（用于断言落库内容）。 */
    private static Collection<Object> setValues(LambdaUpdateWrapper<?> wrapper) {
        return wrapper.getParamNameValuePairs().values();
    }

    @Test
    @DisplayName("LQ happy：2 READY + 1 FAILED → 媒体逐条落库（lqSize 正确）、item=PARTIALLY_SUCCEEDED、progress=100、任务聚合")
    void lqHappy_mixed_partialSucceeded() throws Exception {
        when(managementTaskItemMapper.selectById(200L)).thenReturn(lqItem());
        when(mediaMapper.selectById(1L)).thenReturn(lqMedia(1L, 5L, 1, "5/001.jpg", LqStatus.QUEUED));
        when(mediaMapper.selectById(2L)).thenReturn(lqMedia(2L, 5L, 2, "5/002.jpg", LqStatus.GENERATING));
        when(mediaMapper.selectById(3L)).thenReturn(lqMedia(3L, 5L, 3, "5/003.jpg", LqStatus.QUEUED));
        when(managementTaskItemMapper.update(isNull(), any())).thenReturn(1);

        LqGenerationResult result = new LqGenerationResult(List.of(
                lqReady(1L, 1, "5/001.jpg", "5/001.webp", 512L),
                lqReady(2L, 2, "5/002.jpg", "5/002.webp", 256L),
                lqFailed(3L, 3, "5/003.jpg")), 2, 1, 3);
        handler.handleResult(lqCompletedEvent(result), channel, 1L);

        // item CAS → PARTIALLY_SUCCEEDED + progress=100
        ArgumentCaptor<LambdaUpdateWrapper> itemCaptor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(managementTaskItemMapper).update(isNull(), itemCaptor.capture());
        assertThat(setValues(itemCaptor.getValue())).contains(
                ManagementTaskStatus.PARTIALLY_SUCCEEDED, 100);

        // 媒体逐条落库（3 条）：READY 页带 lqRoot/lqPath/lqSize，FAILED 页只置 FAILED
        ArgumentCaptor<LambdaUpdateWrapper> mediaCaptor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(mediaMapper, times(3)).update(isNull(), mediaCaptor.capture());
        LambdaUpdateWrapper<?> ready1 = mediaCaptor.getAllValues().stream()
                .filter(w -> setValues(w).contains("5/001.webp")).findFirst().orElseThrow();
        assertThat(setValues(ready1)).contains(LqStatus.READY, "LQ", "5/001.webp", 512L);
        LambdaUpdateWrapper<?> ready2 = mediaCaptor.getAllValues().stream()
                .filter(w -> setValues(w).contains("5/002.webp")).findFirst().orElseThrow();
        assertThat(setValues(ready2)).contains(256L);
        LambdaUpdateWrapper<?> failed = mediaCaptor.getAllValues().stream()
                .filter(w -> setValues(w).contains(LqStatus.FAILED))
                .findFirst().orElseThrow();
        assertThat(setValues(failed)).contains(LqStatus.FAILED);

        verify(managementTaskService).reaggregateTask(20L);
        verify(inboxService).markProcessed(anyString(), anyString(), eq(20L), eq(200L), eq(1));
        verify(channel).basicAck(1L, false);
    }

    @Test
    @DisplayName("LQ 全成功 → item=SUCCEEDED；全失败 → item=FAILED")
    void lqAllSuccess_orAllFailed() throws Exception {
        when(managementTaskItemMapper.selectById(200L)).thenReturn(lqItem());
        when(mediaMapper.selectById(1L)).thenReturn(lqMedia(1L, 5L, 1, "5/001.jpg", LqStatus.QUEUED));
        when(mediaMapper.selectById(2L)).thenReturn(lqMedia(2L, 5L, 2, "5/002.jpg", LqStatus.GENERATING));
        when(managementTaskItemMapper.update(isNull(), any())).thenReturn(1);

        LqGenerationResult allSuccess = new LqGenerationResult(List.of(
                lqReady(1L, 1, "5/001.jpg", "5/001.webp", 512L),
                lqReady(2L, 2, "5/002.jpg", "5/002.webp", 256L)), 2, 0, 2);
        handler.handleResult(lqCompletedEvent(allSuccess), channel, 1L);
        ArgumentCaptor<LambdaUpdateWrapper> successCaptor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(managementTaskItemMapper).update(isNull(), successCaptor.capture());
        assertThat(setValues(successCaptor.getValue())).contains(ManagementTaskStatus.SUCCEEDED);
        verify(mediaMapper, times(2)).update(isNull(), any(LambdaUpdateWrapper.class));
        verify(managementTaskService).reaggregateTask(20L);

        // 全失败 → item=FAILED，媒体只置 FAILED
        when(managementTaskItemMapper.selectById(200L)).thenReturn(lqItem());
        when(mediaMapper.selectById(1L)).thenReturn(lqMedia(1L, 5L, 1, "5/001.jpg", LqStatus.QUEUED));
        when(managementTaskItemMapper.update(isNull(), any())).thenReturn(1);
        LqGenerationResult allFailed = new LqGenerationResult(List.of(lqFailed(1L, 1, "5/001.jpg")), 0, 1, 1);
        handler.handleResult(lqCompletedEvent(allFailed), channel, 1L);
        ArgumentCaptor<LambdaUpdateWrapper> failedCaptor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(managementTaskItemMapper, times(2)).update(isNull(), failedCaptor.capture());
        assertThat(setValues(failedCaptor.getAllValues().get(1))).contains(ManagementTaskStatus.FAILED);
    }

    @Test
    @DisplayName("LQ 幂等·同 eventId（Inbox 已处理）：零更新，ACK")
    void lqReplay_sameEventId_acked() throws Exception {
        when(inboxService.isProcessed(anyString(), anyString())).thenReturn(true);

        LqGenerationResult result = new LqGenerationResult(List.of(
                lqReady(1L, 1, "5/001.jpg", "5/001.webp", 512L)), 1, 0, 1);
        handler.handleResult(lqCompletedEvent(result), channel, 1L);

        verifyNoInteractions(mediaMapper);
        verify(managementTaskItemMapper, never()).update(any(), any());
        verify(channel).basicAck(1L, false);
    }

    @Test
    @DisplayName("LQ 幂等·不同 eventId 同 payload：item CAS 0 行 → 媒体零更新、不聚合，ACK")
    void lqReplay_differentEventId_casLoser() throws Exception {
        when(managementTaskItemMapper.selectById(200L)).thenReturn(lqItem());
        when(mediaMapper.selectById(1L)).thenReturn(lqMedia(1L, 5L, 1, "5/001.jpg", LqStatus.QUEUED));
        when(managementTaskItemMapper.update(isNull(), any())).thenReturn(0);

        LqGenerationResult result = new LqGenerationResult(List.of(
                lqReady(1L, 1, "5/001.jpg", "5/001.webp", 512L)), 1, 0, 1);
        handler.handleResult(lqCompletedEvent(result), channel, 1L);

        verify(mediaMapper, never()).update(any(), any());
        verify(managementTaskItemMapper).update(isNull(), any(LambdaUpdateWrapper.class));
        verify(managementTaskService, never()).reaggregateTask(any());
        verify(channel).basicAck(1L, false);
    }

    @Test
    @DisplayName("LQ 幂等·旧 attempt：item attempt 不匹配 → 零更新，ACK")
    void lqReplay_staleAttempt_acked() throws Exception {
        ManagementTaskItem item = lqItem();
        item.setAttempt(2);
        when(managementTaskItemMapper.selectById(200L)).thenReturn(item);

        LqGenerationResult result = new LqGenerationResult(List.of(
                lqReady(1L, 1, "5/001.jpg", "5/001.webp", 512L)), 1, 0, 1);
        handler.handleResult(lqCompletedEvent(result), channel, 1L);

        verify(mediaMapper, never()).update(any(), any());
        verify(managementTaskItemMapper, never()).update(any(), any());
        verify(channel).basicAck(1L, false);
    }

    @Test
    @DisplayName("LQ 幂等·已终态 item：零更新，ACK")
    void lqReplay_terminalItem_acked() throws Exception {
        ManagementTaskItem item = lqItem();
        item.setStatus(ManagementTaskStatus.SUCCEEDED);
        when(managementTaskItemMapper.selectById(200L)).thenReturn(item);

        LqGenerationResult result = new LqGenerationResult(List.of(
                lqReady(1L, 1, "5/001.jpg", "5/001.webp", 512L)), 1, 0, 1);
        handler.handleResult(lqCompletedEvent(result), channel, 1L);

        verify(mediaMapper, never()).update(any(), any());
        verify(managementTaskItemMapper, never()).update(any(), any());
        verify(channel).basicAck(1L, false);
    }

    @Test
    @DisplayName("LQ 越权·mediaId 不属于目标章节：媒体零更新、item FAILED、ACK")
    void lqEscalation_mediaNotInTargetChapter() throws Exception {
        when(managementTaskItemMapper.selectById(200L)).thenReturn(lqItem());
        when(mediaMapper.selectById(1L)).thenReturn(lqMedia(1L, 999L, 1, "999/001.jpg", LqStatus.QUEUED));
        when(managementTaskItemMapper.update(isNull(), any())).thenReturn(1);

        LqGenerationResult result = new LqGenerationResult(List.of(
                lqReady(1L, 1, "999/001.jpg", "5/001.webp", 512L)), 1, 0, 1);
        handler.handleResult(lqCompletedEvent(result), channel, 1L);

        verify(mediaMapper, never()).update(any(), any());
        ArgumentCaptor<LambdaUpdateWrapper> itemCaptor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(managementTaskItemMapper).update(isNull(), itemCaptor.capture());
        assertThat(setValues(itemCaptor.getValue())).contains(ManagementTaskStatus.FAILED);
        verify(managementTaskService).reaggregateTask(20L);
        verify(channel).basicAck(1L, false);
    }

    @Test
    @DisplayName("LQ 越权·sourceHqPath 与 DB 不一致：媒体零更新、item FAILED、ACK")
    void lqEscalation_sourceHqPathMismatch() throws Exception {
        when(managementTaskItemMapper.selectById(200L)).thenReturn(lqItem());
        when(mediaMapper.selectById(1L)).thenReturn(lqMedia(1L, 5L, 1, "5/001.jpg", LqStatus.QUEUED));
        when(managementTaskItemMapper.update(isNull(), any())).thenReturn(1);

        LqGenerationResult result = new LqGenerationResult(List.of(
                lqReady(1L, 1, "5/OTHER.jpg", "5/001.webp", 512L)), 1, 0, 1);
        handler.handleResult(lqCompletedEvent(result), channel, 1L);

        verify(mediaMapper, never()).update(any(), any());
        ArgumentCaptor<LambdaUpdateWrapper> itemCaptor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(managementTaskItemMapper).update(isNull(), itemCaptor.capture());
        assertThat(setValues(itemCaptor.getValue())).contains(ManagementTaskStatus.FAILED);
        verify(channel).basicAck(1L, false);
    }

    @Test
    @DisplayName("LQ 旧协议·lqResult 为 null：item FAILED 并 ACK，不猜整章结果")
    void lqPayloadNull_oldProtocol_failsItem() throws Exception {
        when(managementTaskItemMapper.selectById(200L)).thenReturn(lqItem());
        when(managementTaskItemMapper.update(isNull(), any())).thenReturn(1);

        ManagementCommandCompletedEvent ev = new ManagementCommandCompletedEvent(
                UUID.randomUUID(), Instant.now(), 1,
                20L, 200L, 1, "LQ_GENERATE", "CHAPTER", 5L, null);
        handler.handleResult(ev, channel, 1L);

        verify(mediaMapper, never()).update(any(), any());
        ArgumentCaptor<LambdaUpdateWrapper> itemCaptor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(managementTaskItemMapper).update(isNull(), itemCaptor.capture());
        assertThat(setValues(itemCaptor.getValue())).contains(ManagementTaskStatus.FAILED);
        verify(managementTaskService).reaggregateTask(20L);
        verify(channel).basicAck(1L, false);
    }

    @Test
    @DisplayName("LQ 基础设施异常·mapper 抛出：异常传播 → reject/DLQ，不 ACK")
    void lqInfraFailure_mapperThrows_rejectsToDlq() throws Exception {
        when(managementTaskItemMapper.selectById(200L)).thenReturn(lqItem());
        when(mediaMapper.selectById(1L)).thenThrow(new RuntimeException("数据库不可用"));

        LqGenerationResult result = new LqGenerationResult(List.of(
                lqReady(1L, 1, "5/001.jpg", "5/001.webp", 512L)), 1, 0, 1);
        handler.handleResult(lqCompletedEvent(result), channel, 1L);

        verify(channel).basicReject(1L, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    // ======================== COMIC 目标 LQ（批量 LQ 分流，Todo P1） ========================

    private ManagementTaskItem lqComicItem() {
        ManagementTaskItem item = new ManagementTaskItem();
        item.setId(300L);
        item.setTaskId(30L);
        item.setTargetType("COMIC");
        item.setTargetId(1L);
        item.setOperationType(TaskType.LQ_GENERATE);
        item.setStatus(ManagementTaskStatus.RUNNING);
        item.setAttempt(1);
        return item;
    }

    private ManagementCommandCompletedEvent lqComicCompletedEvent(LqGenerationResult result) {
        return new ManagementCommandCompletedEvent(UUID.randomUUID(), Instant.now(), 1,
                30L, 300L, 1, "LQ_GENERATE", "COMIC", 1L, null, result);
    }

    private Chapter chapter(Long id, Long comicId) {
        Chapter chapter = new Chapter();
        chapter.setId(id);
        chapter.setComicId(comicId);
        return chapter;
    }

    @Test
    @DisplayName("COMIC LQ：跨两章 2 READY + 1 FAILED 混合结果逐媒体落库、item=PARTIALLY_SUCCEEDED、任务聚合、ACK")
    void lqComic_crossChapter_mixedResult_appliesPerMedia() throws Exception {
        when(managementTaskItemMapper.selectById(300L)).thenReturn(lqComicItem());
        when(mediaMapper.selectById(1L)).thenReturn(lqMedia(1L, 5L, 1, "5/001.jpg", LqStatus.QUEUED));
        when(mediaMapper.selectById(2L)).thenReturn(lqMedia(2L, 6L, 1, "6/001.jpg", LqStatus.GENERATING));
        when(mediaMapper.selectById(3L)).thenReturn(lqMedia(3L, 6L, 2, "6/002.jpg", LqStatus.QUEUED));
        when(chapterMapper.selectById(5L)).thenReturn(chapter(5L, 1L));
        when(chapterMapper.selectById(6L)).thenReturn(chapter(6L, 1L));
        when(managementTaskItemMapper.update(isNull(), any())).thenReturn(1);

        LqGenerationResult result = new LqGenerationResult(List.of(
                lqReady(1L, 1, "5/001.jpg", "5/001.webp", 512L),
                lqReady(2L, 1, "6/001.jpg", "6/001.webp", 256L),
                lqFailed(3L, 2, "6/002.jpg")), 2, 1, 3);
        handler.handleResult(lqComicCompletedEvent(result), channel, 1L);

        // item CAS → PARTIALLY_SUCCEEDED + progress=100
        ArgumentCaptor<LambdaUpdateWrapper> itemCaptor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(managementTaskItemMapper).update(isNull(), itemCaptor.capture());
        assertThat(setValues(itemCaptor.getValue())).contains(
                ManagementTaskStatus.PARTIALLY_SUCCEEDED, 100);

        // 跨章节媒体逐条落库（3 条），不因 targetId=comicId 误拒
        verify(mediaMapper, times(3)).update(isNull(), any(LambdaUpdateWrapper.class));
        verify(managementTaskService).reaggregateTask(30L);
        verify(channel).basicAck(1L, false);
    }

    @Test
    @DisplayName("COMIC LQ 越权：media 经 chapter 归属非目标漫画 → item FAILED、媒体零更新、ACK")
    void lqComicEscalation_mediaNotInTargetComic() throws Exception {
        when(managementTaskItemMapper.selectById(300L)).thenReturn(lqComicItem());
        when(mediaMapper.selectById(1L)).thenReturn(lqMedia(1L, 999L, 1, "999/001.jpg", LqStatus.QUEUED));
        when(chapterMapper.selectById(999L)).thenReturn(chapter(999L, 999L));
        when(managementTaskItemMapper.update(isNull(), any())).thenReturn(1);

        LqGenerationResult result = new LqGenerationResult(List.of(
                lqReady(1L, 1, "999/001.jpg", "999/001.webp", 512L)), 1, 0, 1);
        handler.handleResult(lqComicCompletedEvent(result), channel, 1L);

        verify(mediaMapper, never()).update(any(), any());
        ArgumentCaptor<LambdaUpdateWrapper> itemCaptor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(managementTaskItemMapper).update(isNull(), itemCaptor.capture());
        assertThat(setValues(itemCaptor.getValue())).contains(ManagementTaskStatus.FAILED);
        verify(managementTaskService).reaggregateTask(30L);
        verify(channel).basicAck(1L, false);
    }

    @Test
    @DisplayName("COMIC LQ 旧 attempt：零更新 ACK")
    void lqComic_staleAttempt_acked() throws Exception {
        ManagementTaskItem item = lqComicItem();
        item.setAttempt(2);
        when(managementTaskItemMapper.selectById(300L)).thenReturn(item);

        LqGenerationResult result = new LqGenerationResult(List.of(
                lqReady(1L, 1, "5/001.jpg", "5/001.webp", 512L)), 1, 0, 1);
        handler.handleResult(lqComicCompletedEvent(result), channel, 1L);

        verify(mediaMapper, never()).update(any(), any());
        verify(managementTaskItemMapper, never()).update(any(), any());
        verify(channel).basicAck(1L, false);
    }

    @Test
    @DisplayName("COMIC LQ 重复事件·item 已终态：幂等零更新 ACK")
    void lqComicReplay_terminalItem_acked() throws Exception {
        ManagementTaskItem item = lqComicItem();
        item.setStatus(ManagementTaskStatus.SUCCEEDED);
        when(managementTaskItemMapper.selectById(300L)).thenReturn(item);

        LqGenerationResult result = new LqGenerationResult(List.of(
                lqReady(1L, 1, "5/001.jpg", "5/001.webp", 512L)), 1, 0, 1);
        handler.handleResult(lqComicCompletedEvent(result), channel, 1L);

        verify(mediaMapper, never()).update(any(), any());
        verify(managementTaskItemMapper, never()).update(any(), any());
        verify(channel).basicAck(1L, false);
    }

    @Test
    @DisplayName("COMIC LQ 进度：漫画下全部章节 QUEUED 图片 → GENERATING")
    void lqComicProgress_routesAllComicChapters() throws Exception {
        when(inboxService.isProcessed(anyString(), anyString())).thenReturn(false);
        when(managementTaskService.updateItemProgress(anyLong(), anyInt(), anyInt(), anyString()))
                .thenReturn(true);
        when(chapterMapper.selectList(any())).thenReturn(List.of(chapter(5L, 1L), chapter(6L, 1L)));

        ManagementCommandProgressEvent ev = new ManagementCommandProgressEvent(
                UUID.randomUUID(), Instant.now(), 1, 30L, 300L, 1,
                "LQ_GENERATE", "COMIC", 1L, 50, "生成中");
        handler.handleResult(ev, channel, 1L);

        ArgumentCaptor<LambdaUpdateWrapper> mediaCaptor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(mediaMapper).update(isNull(), mediaCaptor.capture());
        assertThat(setValues(mediaCaptor.getValue())).contains(LqStatus.GENERATING);
        // COMIC 作用域：WHERE 必须为 chapter_id IN (章节集合)，而非旧的 chapter_id = comicId
        assertThat(mediaCaptor.getValue().getSqlSegment())
                .contains("chapter_id").contains("IN");
        verify(channel).basicAck(1L, false);
    }

    @Test
    @DisplayName("COMIC LQ 失败：漫画下全部章节 QUEUED/GENERATING 图片 → FAILED")
    void lqComicFailed_marksComicMediaFailed() throws Exception {
        when(inboxService.isProcessed(anyString(), anyString())).thenReturn(false);
        ManagementTaskItemResponse resp = new ManagementTaskItemResponse();
        resp.setStatus(ManagementTaskStatus.FAILED);
        when(managementTaskService.updateItemStatus(eq(300L), eq(ManagementTaskStatus.FAILED),
                anyString(), isNull(), isNull(), eq(1))).thenReturn(resp);
        when(chapterMapper.selectList(any())).thenReturn(List.of(chapter(5L, 1L), chapter(6L, 1L)));

        ManagementCommandFailedEvent ev = new ManagementCommandFailedEvent(
                UUID.randomUUID(), Instant.now(), 1, 30L, 300L, 1,
                "LQ_GENERATE", "COMIC", 1L, "Worker 失败");
        handler.handleResult(ev, channel, 1L);

        ArgumentCaptor<LambdaUpdateWrapper> mediaCaptor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(mediaMapper).update(isNull(), mediaCaptor.capture());
        assertThat(setValues(mediaCaptor.getValue())).contains(LqStatus.FAILED);
        // COMIC 作用域：WHERE 必须为 chapter_id IN (章节集合)，而非旧的 chapter_id = comicId
        assertThat(mediaCaptor.getValue().getSqlSegment())
                .contains("chapter_id").contains("IN");
        verify(channel).basicAck(1L, false);
    }

    // ======================== 转码完成专用流程（Todo 7） ========================

    private ManagementTaskItem transcodeItem(Long itemId, Long taskId, Long mediaId,
                                             ManagementTaskStatus status, int attempt) {
        ManagementTaskItem item = new ManagementTaskItem();
        item.setId(itemId);
        item.setTaskId(taskId);
        item.setTargetType("MEDIA");
        item.setTargetId(mediaId);
        item.setOperationType(TaskType.TRANSCODE);
        item.setStatus(status);
        item.setAttempt(attempt);
        return item;
    }

    private Media transcodeMedia(Long mediaId, TranscodeStatus status) {
        Media m = new Media();
        m.setId(mediaId);
        m.setChapterId(5L);
        m.setMediaType("VIDEO");
        m.setHqRoot("HQ");
        m.setHqPath("5/001.avi");
        m.setHqStatus(HqStatus.READY);
        m.setTranscodeStatus(status);
        m.setStatus(MediaLifecycleStatus.READY);
        return m;
    }

    private TranscodeMediaInfo transcodeInfo() {
        return new TranscodeMediaInfo(new BigDecimal("12.34"), "mp4", "h264", "aac", 2048000L,
                "HQ", "5/001/1-2-3-100.mp4", 1280, 720);
    }

    private ManagementCommandCompletedEvent transcodeCompletedEvent(Long taskId, Long itemId,
                                                                    int attempt, Long mediaId,
                                                                    TranscodeMediaInfo info) {
        return new ManagementCommandCompletedEvent(UUID.randomUUID(), Instant.now(), 1,
                taskId, itemId, attempt, "TRANSCODE", "MEDIA", mediaId, info);
    }

    private ManagementTaskItemResponse failedResp() {
        ManagementTaskItemResponse resp = new ManagementTaskItemResponse();
        resp.setStatus(ManagementTaskStatus.FAILED);
        return resp;
    }

    @Test
    @DisplayName("转码完成：校验通过 → item SUCCEEDED、media 一次更新真实产物与全部元数据、聚合、ACK")
    void transcodeCompleted_happy_appliesRealArtifact() throws Exception {
        when(managementTaskItemMapper.selectById(100L))
                .thenReturn(transcodeItem(100L, 10L, 1L, ManagementTaskStatus.RUNNING, 1));
        when(mediaMapper.selectById(1L)).thenReturn(transcodeMedia(1L, TranscodeStatus.QUEUED));
        when(managementTaskItemMapper.update(isNull(), any())).thenReturn(1);
        when(managementTaskService.countActiveItems(10L)).thenReturn(1L);

        handler.handleResult(transcodeCompletedEvent(10L, 100L, 1, 1L, transcodeInfo()), channel, 1L);

        // item CAS → SUCCEEDED
        ArgumentCaptor<LambdaUpdateWrapper> itemCaptor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(managementTaskItemMapper).update(isNull(), itemCaptor.capture());
        assertThat(itemCaptor.getValue().getParamNameValuePairs().values()).contains(ManagementTaskStatus.SUCCEEDED);
        // media 一次更新：hqRoot/hqPath + width/height/duration/container/codecs/fileSize + hqStatus READY + transcodeStatus READY
        ArgumentCaptor<LambdaUpdateWrapper> mediaCaptor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(mediaMapper).update(isNull(), mediaCaptor.capture());
        assertThat(mediaCaptor.getValue().getSqlSet()).contains("hq_root").contains("hq_path")
                .contains("width").contains("height").contains("duration")
                .contains("container").contains("video_codec").contains("audio_codec")
                .contains("file_size").contains("hq_status").contains("transcode_status");
        assertThat(mediaCaptor.getValue().getParamNameValuePairs().values())
                .contains("HQ", "5/001/1-2-3-100.mp4", 1280, 720, "mp4", "h264", "aac",
                        new BigDecimal("12.34"), 2048000L, TranscodeStatus.READY, HqStatus.READY);
        verify(managementTaskService).reaggregateTask(10L);
        verify(channel).basicAck(1L, false);
    }

    @Test
    @DisplayName("转码完成：事件缺真实产物路径 → item FAILED、media FAILED（保留原 HQ 引用）、ACK")
    void transcodeCompleted_missingTranscode_failsItem() throws Exception {
        when(managementTaskItemMapper.selectById(100L))
                .thenReturn(transcodeItem(100L, 10L, 1L, ManagementTaskStatus.RUNNING, 1));
        when(mediaMapper.selectById(1L)).thenReturn(transcodeMedia(1L, TranscodeStatus.QUEUED));
        when(managementTaskItemMapper.update(isNull(), any())).thenReturn(1);

        ManagementCommandCompletedEvent ev = new ManagementCommandCompletedEvent(
                UUID.randomUUID(), Instant.now(), 1,
                10L, 100L, 1, "TRANSCODE", "MEDIA", 1L, null);
        handler.handleResult(ev, channel, 1L);

        ArgumentCaptor<LambdaUpdateWrapper> itemCaptor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(managementTaskItemMapper).update(isNull(), itemCaptor.capture());
        assertThat(itemCaptor.getValue().getParamNameValuePairs().values()).contains(ManagementTaskStatus.FAILED);
        ArgumentCaptor<LambdaUpdateWrapper> mediaCaptor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(mediaMapper).update(isNull(), mediaCaptor.capture());
        assertThat(mediaCaptor.getValue().getParamNameValuePairs().values()).contains(TranscodeStatus.FAILED);
        verify(managementTaskService).reaggregateTask(10L);
        verify(channel).basicAck(1L, false);
    }

    @Test
    @DisplayName("转码完成：产物存储根未配置（containment 无法通过）→ 业务失败，item FAILED、media FAILED、ACK")
    void transcodeCompleted_pathTraversal_failsItem() throws Exception {
        when(managementTaskItemMapper.selectById(100L))
                .thenReturn(transcodeItem(100L, 10L, 1L, ManagementTaskStatus.RUNNING, 1));
        when(mediaMapper.selectById(1L)).thenReturn(transcodeMedia(1L, TranscodeStatus.TRANSCODING));
        when(managementTaskItemMapper.update(isNull(), any())).thenReturn(1);

        // 事件 DTO 已内建相对路径校验（拒绝 ../ 穿越），containment 防御的失败分支是存储根缺失/越界
        TranscodeMediaInfo unknownRoot = new TranscodeMediaInfo(new BigDecimal("1"), "mp4", "h264", "aac", 10L,
                "UNKNOWN", "5/001.mp4", 10, 10);
        handler.handleResult(transcodeCompletedEvent(10L, 100L, 1, 1L, unknownRoot), channel, 1L);

        ArgumentCaptor<LambdaUpdateWrapper> itemCaptor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(managementTaskItemMapper).update(isNull(), itemCaptor.capture());
        assertThat(itemCaptor.getValue().getParamNameValuePairs().values()).contains(ManagementTaskStatus.FAILED);
        verify(mediaMapper).update(isNull(), any(LambdaUpdateWrapper.class));
        verify(managementTaskService).reaggregateTask(10L);
        verify(channel).basicAck(1L, false);
    }

    @Test
    @DisplayName("转码完成：旧 attempt → 零更新 ACK")
    void transcodeCompleted_staleAttempt_acked() throws Exception {
        when(managementTaskItemMapper.selectById(100L))
                .thenReturn(transcodeItem(100L, 10L, 1L, ManagementTaskStatus.RUNNING, 2));

        handler.handleResult(transcodeCompletedEvent(10L, 100L, 1, 1L, transcodeInfo()), channel, 1L);

        verify(mediaMapper, never()).update(any(), any());
        verify(managementTaskItemMapper, never()).update(any(), any());
        verify(channel).basicAck(1L, false);
    }

    @Test
    @DisplayName("转码完成：item 已终态 → 幂等零更新 ACK")
    void transcodeCompleted_terminalItem_acked() throws Exception {
        when(managementTaskItemMapper.selectById(100L))
                .thenReturn(transcodeItem(100L, 10L, 1L, ManagementTaskStatus.SUCCEEDED, 1));

        handler.handleResult(transcodeCompletedEvent(10L, 100L, 1, 1L, transcodeInfo()), channel, 1L);

        verify(mediaMapper, never()).update(any(), any());
        verify(managementTaskItemMapper, never()).update(any(), any());
        verify(channel).basicAck(1L, false);
    }

    @Test
    @DisplayName("转码完成：事件 targetId 与 item 不一致 → item FAILED、ACK")
    void transcodeCompleted_targetMismatch_failsItem() throws Exception {
        when(managementTaskItemMapper.selectById(100L))
                .thenReturn(transcodeItem(100L, 10L, 99L, ManagementTaskStatus.RUNNING, 1));
        when(managementTaskItemMapper.update(isNull(), any())).thenReturn(1);

        handler.handleResult(transcodeCompletedEvent(10L, 100L, 1, 1L, transcodeInfo()), channel, 1L);

        ArgumentCaptor<LambdaUpdateWrapper> itemCaptor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(managementTaskItemMapper).update(isNull(), itemCaptor.capture());
        assertThat(itemCaptor.getValue().getParamNameValuePairs().values()).contains(ManagementTaskStatus.FAILED);
        verify(mediaMapper).update(isNull(), any(LambdaUpdateWrapper.class));
        verify(channel).basicAck(1L, false);
    }

    @Test
    @DisplayName("转码完成：目标类型非 MEDIA → item FAILED、ACK")
    void transcodeCompleted_nonMediaTarget_failsItem() throws Exception {
        when(managementTaskItemMapper.update(isNull(), any())).thenReturn(1);

        ManagementCommandCompletedEvent ev = new ManagementCommandCompletedEvent(
                UUID.randomUUID(), Instant.now(), 1,
                10L, 100L, 1, "TRANSCODE", "COMIC", 42L, transcodeInfo());
        handler.handleResult(ev, channel, 1L);

        ArgumentCaptor<LambdaUpdateWrapper> itemCaptor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(managementTaskItemMapper).update(isNull(), itemCaptor.capture());
        assertThat(itemCaptor.getValue().getParamNameValuePairs().values()).contains(ManagementTaskStatus.FAILED);
        verify(mediaMapper, never()).update(any(), any());
        verify(channel).basicAck(1L, false);
    }

    @Test
    @DisplayName("转码失败事件：仅当前 QUEUED/TRANSCODING 媒体置 FAILED，保留原 HQ 引用，item FAILED")
    void transcodeFailedEvent_marksMediaFailed_keepingHqRef() throws Exception {
        when(managementTaskService.updateItemStatus(eq(100L), eq(ManagementTaskStatus.FAILED),
                anyString(), isNull(), isNull(), eq(1))).thenReturn(failedResp());

        ManagementCommandFailedEvent ev = new ManagementCommandFailedEvent(
                UUID.randomUUID(), Instant.now(), 1,
                10L, 100L, 1, "TRANSCODE", "MEDIA", 1L, "ffmpeg exit 1");
        handler.handleResult(ev, channel, 1L);

        ArgumentCaptor<LambdaUpdateWrapper> mediaCaptor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(mediaMapper).update(isNull(), mediaCaptor.capture());
        assertThat(mediaCaptor.getValue().getParamNameValuePairs().values()).contains(TranscodeStatus.FAILED);
        verify(channel).basicAck(1L, false);
    }
}
