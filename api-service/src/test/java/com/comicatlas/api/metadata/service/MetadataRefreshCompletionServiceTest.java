package com.comicatlas.api.metadata.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.comicatlas.api.catalog.cache.CatalogCacheInvalidator;
import com.comicatlas.api.task.entity.ManagementTaskItem;
import com.comicatlas.api.task.mapper.ManagementTaskItemMapper;
import com.comicatlas.api.task.service.ManagementTaskService;
import com.comicatlas.api.outbox.service.InboxService;
import com.comicatlas.api.outbox.service.EventFingerprintService;
import com.comicatlas.api.outbox.service.OutboxService;
import com.comicatlas.api.metadata.service.MetadataRefreshService.MetadataRefreshLoadRequest;
import com.comicatlas.common.constant.MqExchanges;
import com.comicatlas.common.constant.MqRoutingKeys;
import com.comicatlas.common.dto.MetadataRefreshSnapshotDTO;
import com.comicatlas.common.event.MetadataRefreshEvent;
import com.comicatlas.common.event.MetadataRefreshScanCompletedEvent;
import com.comicatlas.contract.common.enums.ComicStatus;
import com.comicatlas.api.task.enums.ManagementTaskStatus;
import com.comicatlas.api.task.enums.TaskType;
import com.comicatlas.contract.common.exception.BusinessException;
import com.comicatlas.api.shared.exception.SnapshotUnavailableException;
import com.comicatlas.persistence.comic.entity.Comic;
import com.comicatlas.persistence.comic.mapper.ComicMapper;
import com.comicatlas.api.storage.ApiStorageProperties;
import com.comicatlas.api.storage.ApiStorageRoot;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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
 * MetadataRefreshCompletionService 元数据扫盘刷新完成事件专用流程单元测试。
 * <p>
 * 覆盖：happy（item SUCCEEDED + comic READY + Inbox + Outbox + 快照清理）、幂等前置检查、
 * 同 attempt 不同 eventId 的 CAS 败者跳过、业务失败（SHA 篡改/revision 漂移）走失败短事务、
 * 基础设施失败（Outbox 异常 / 快照不可用）异常向上传播（消费入口 reject/DLQ）不伪造成功。
 * <p>
 * 事务边界：快照读取/校验在事务外（mock 验证顺序由生产代码保证），
 * 所有 DB 写入在一个短事务内（mock TransactionTemplate 直接执行回调）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MetadataRefreshCompletionServiceTest — 元数据刷新完成专用流程")
class MetadataRefreshCompletionServiceTest {

    @Mock private ManagementTaskItemMapper managementTaskItemMapper;
    @Mock private ComicMapper comicMapper;
    @Mock private MetadataRefreshService metadataRefreshService;
    @Mock private InboxService inboxService;
    @Mock private EventFingerprintService eventFingerprintService;
    @Mock private OutboxService outboxService;
    @Mock private ManagementTaskService managementTaskService;
    @Mock private CatalogCacheInvalidator catalogCacheInvalidator;
    @Mock private ApiStorageProperties apiStorageProperties;
    @Mock private TransactionTemplate transactionTemplate;
    @Spy private ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @InjectMocks private MetadataRefreshCompletionService service;

    @TempDir Path tempDir;

    private Path staging;
    private Path snapshotDir;

    @BeforeEach
    void setUp() throws Exception {
        lenient().when(eventFingerprintService.fingerprint(any())).thenReturn("test-event-hash");
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
    @DisplayName("happy：completed → item SUCCEEDED、comic READY、Inbox、Outbox 入箱 MetadataRefreshEvent、快照清理")
    void happy_completed_appliesAndCleansSnapshot() throws Exception {
        when(managementTaskItemMapper.selectById(100L)).thenReturn(runningItem());
        when(metadataRefreshService.loadAndValidate(any())).thenReturn(snapshot());
        when(comicMapper.selectByIdForUpdate(1L)).thenReturn(refreshingComic());
        when(comicMapper.update(isNull(), any())).thenReturn(1);
        when(managementTaskItemMapper.update(isNull(), any())).thenReturn(1);

        MetadataRefreshScanCompletedEvent ev = completedEvent();
        service.handleCompleted(ev);

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
    }

    // ======================== 幂等前置检查（事务外，直接返回） ========================

    @Test
    @DisplayName("item 不存在（幽灵事件）：不读取快照")
    void ghostItem_ignored() throws Exception {
        when(managementTaskItemMapper.selectById(100L)).thenReturn(null);

        service.handleCompleted(completedEvent());

        verifyNoInteractions(metadataRefreshService, outboxService);
    }

    @Test
    @DisplayName("item 已终态：不二次 apply")
    void terminalItem_ignored() throws Exception {
        ManagementTaskItem item = runningItem();
        item.setStatus(ManagementTaskStatus.SUCCEEDED);
        when(managementTaskItemMapper.selectById(100L)).thenReturn(item);

        service.handleCompleted(completedEvent());

        verifyNoInteractions(metadataRefreshService, outboxService);
    }

    @Test
    @DisplayName("不同 attempt：忽略旧 attempt 完成结果")
    void staleAttempt_ignored() throws Exception {
        ManagementTaskItem item = runningItem();
        item.setAttempt(2);
        when(managementTaskItemMapper.selectById(100L)).thenReturn(item);

        service.handleCompleted(completedEvent());

        verifyNoInteractions(metadataRefreshService, outboxService);
    }

    @Test
    @DisplayName("operationType 非 METADATA_REFRESH：防御性忽略")
    void wrongOperation_ignored() throws Exception {
        ManagementTaskItem item = runningItem();
        item.setOperationType(TaskType.LQ_GENERATE);
        when(managementTaskItemMapper.selectById(100L)).thenReturn(item);

        service.handleCompleted(completedEvent());

        verifyNoInteractions(metadataRefreshService, outboxService);
    }

    // ======================== 同 attempt 不同 eventId：CAS 败者 ========================

    @Test
    @DisplayName("item CAS 0 行（已被其他 eventId 处理）：不 apply、不写 Outbox，快照保留")
    void casLoser_skipsApplyAndKeepsSnapshot() throws Exception {
        when(managementTaskItemMapper.selectById(100L)).thenReturn(runningItem());
        when(metadataRefreshService.loadAndValidate(any())).thenReturn(snapshot());
        when(comicMapper.selectByIdForUpdate(1L)).thenReturn(refreshingComic());
        when(comicMapper.update(isNull(), any())).thenReturn(1);
        when(managementTaskItemMapper.update(isNull(), any())).thenReturn(0);

        MetadataRefreshScanCompletedEvent ev = completedEvent();
        service.handleCompleted(ev);

        // 败者：不做差异合并、不重导出、不聚合
        verify(metadataRefreshService, never()).applyValidatedSnapshot(any());
        verify(outboxService, never()).enqueue(any(), anyString(), anyString());
        verify(managementTaskService, never()).reaggregateTask(any());
        // 仍记录 Inbox（eventId 幂等键）
        verify(inboxService).markProcessed(eq(ev.eventId().toString()), anyString(), eq(10L), eq(100L), eq(1));
        // 快照保留（胜者负责清理）
        assertThat(Files.exists(snapshotDir)).isTrue();
    }

    // ======================== 业务失败：失败短事务 + 快照保留 ========================

    @Test
    @DisplayName("loadAndValidate 业务失败（SHA 篡改）：item/task FAILED、comic READY、Inbox、快照保留")
    void loadValidateBusinessFailure_failsItemAndReleasesComic() throws Exception {
        when(managementTaskItemMapper.selectById(100L)).thenReturn(runningItem());
        when(metadataRefreshService.loadAndValidate(any()))
                .thenThrow(new BusinessException("快照 SHA-256 与事件声明不一致"));
        when(managementTaskItemMapper.update(isNull(), any())).thenReturn(1);
        when(comicMapper.update(isNull(), any())).thenReturn(1);

        MetadataRefreshScanCompletedEvent ev = completedEvent();
        service.handleCompleted(ev);

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
    }

    @Test
    @DisplayName("apply 复核 databaseRevision 漂移：整事务回滚后走失败短事务，快照保留")
    void applyRevisionDrift_failsItemAndReleasesComic() throws Exception {
        when(managementTaskItemMapper.selectById(100L)).thenReturn(runningItem());
        when(metadataRefreshService.loadAndValidate(any())).thenReturn(snapshot());
        when(comicMapper.selectByIdForUpdate(1L)).thenReturn(refreshingComic());
        when(comicMapper.update(isNull(), any())).thenReturn(1);
        when(managementTaskItemMapper.update(isNull(), any())).thenReturn(1);
        when(metadataRefreshService.applyValidatedSnapshot(any()))
                .thenThrow(new BusinessException("快照结构摘要与自带 databaseRevision 不一致"));

        MetadataRefreshScanCompletedEvent ev = completedEvent();
        service.handleCompleted(ev);

        // 成功短事务内 apply 已调用（抛出后整体回滚），随后失败短事务执行
        verify(metadataRefreshService).applyValidatedSnapshot(any());
        verify(managementTaskItemMapper, times(2)).update(isNull(), any(LambdaUpdateWrapper.class));
        verify(comicMapper, times(2)).update(isNull(), any(LambdaUpdateWrapper.class));
        verify(managementTaskService).reaggregateTask(10L);
        verify(inboxService).markProcessed(eq(ev.eventId().toString()), anyString(), eq(10L), eq(100L), eq(1));
        verify(outboxService, never()).enqueue(any(), anyString(), anyString());
        assertThat(Files.exists(snapshotDir)).isTrue();
    }

    // ======================== 基础设施失败：异常向上传播（消费入口 reject/DLQ），不伪造成功 ========================

    @Test
    @DisplayName("Outbox 入箱抛异常：异常向上传播，不走失败短事务")
    void infraFailure_outboxThrows_propagatesToReject() throws Exception {
        when(managementTaskItemMapper.selectById(100L)).thenReturn(runningItem());
        when(metadataRefreshService.loadAndValidate(any())).thenReturn(snapshot());
        when(comicMapper.selectByIdForUpdate(1L)).thenReturn(refreshingComic());
        when(comicMapper.update(isNull(), any())).thenReturn(1);
        when(managementTaskItemMapper.update(isNull(), any())).thenReturn(1);
        when(metadataRefreshService.applyValidatedSnapshot(any())).thenReturn(null);
        doThrow(new RuntimeException("Outbox 序列化失败"))
                .when(outboxService).enqueue(any(MetadataRefreshEvent.class), anyString(), anyString(), any(), any(), anyInt());

        MetadataRefreshScanCompletedEvent ev = completedEvent();
        assertThatThrownBy(() -> service.handleCompleted(ev))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Outbox 序列化失败");

        // 基础设施故障 → 异常传播（消费入口 reject/DLQ），任务聚合未执行（事务未提交）
        verify(managementTaskService, never()).reaggregateTask(any());
        // 快照保留（未到提交后清理）
        assertThat(Files.exists(snapshotDir)).isTrue();
    }

    @Test
    @DisplayName("快照文件不可用（IOException 被包装为业务异常）：异常向上传播")
    void infraFailure_snapshotIo_propagates() throws Exception {
        when(managementTaskItemMapper.selectById(100L)).thenReturn(runningItem());
        when(metadataRefreshService.loadAndValidate(any()))
                .thenThrow(new SnapshotUnavailableException("快照读取失败", new IOException("文件不存在")));

        assertThatThrownBy(() -> service.handleCompleted(completedEvent()))
                .isInstanceOf(SnapshotUnavailableException.class);

        // 失败短事务未执行（不标记 FAILED，等 DLQ 重放）
        verify(managementTaskItemMapper, never()).update(isNull(), any(LambdaUpdateWrapper.class));
    }

    @Test
    @DisplayName("快照文件缺失（Files.isRegularFile 返回 false，无 IO cause）：异常向上传播")
    void infraFailure_snapshotNotRegularFile_propagates() throws Exception {
        when(managementTaskItemMapper.selectById(100L)).thenReturn(runningItem());
        when(metadataRefreshService.loadAndValidate(any()))
                .thenThrow(new SnapshotUnavailableException("快照产物不可用（非常规文件或符号链接）: snapshot.json"));

        assertThatThrownBy(() -> service.handleCompleted(completedEvent()))
                .isInstanceOf(SnapshotUnavailableException.class);

        verify(managementTaskItemMapper, never()).update(isNull(), any(LambdaUpdateWrapper.class));
    }
}
