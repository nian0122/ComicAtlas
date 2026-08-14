package com.comicatlas.api.importer.event;

import com.comicatlas.persistence.comic.entity.Comic;
import com.comicatlas.persistence.comic.mapper.ComicMapper;
import com.comicatlas.api.importer.entity.ImportTask;
import com.comicatlas.api.importer.mapper.ImportTaskMapper;
import com.comicatlas.api.importer.service.ImportPersistenceService;
import com.comicatlas.contract.common.enums.ComicStatus;
import com.comicatlas.contract.common.enums.ImportTaskStatus;
import com.comicatlas.persistence.storage.ApiStorageProperties;
import com.comicatlas.persistence.storage.ApiStorageRoot;
import com.comicatlas.api.management.service.ManagementTaskService;
import com.comicatlas.common.event.ImportTaskCompletedEvent;
import com.comicatlas.common.event.ImportTaskFailedEvent;
import com.comicatlas.common.event.TaskStatusChangedEvent;
import com.comicatlas.common.mq.MqConsumerSupport;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.File;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ImportEventHandlerCacheTest {

    @Mock private ObjectMapper objectMapper;
    @Mock private RedisTemplate<String, Object> redisTemplate;
    @Mock private ValueOperations<String, Object> valueOperations;
    @Mock private ComicMapper comicMapper;
    @Mock private ImportTaskMapper taskMapper;
    @Mock private TransactionTemplate transactionTemplate;
    @Mock private ManagementTaskService managementTaskService;
    @Mock private ApiStorageProperties storageProperties;
    @Mock private ImportPersistenceService importPersistenceService;
    @Mock private Channel channel;
    @Spy private MqConsumerSupport mqConsumerSupport = new MqConsumerSupport();
    @InjectMocks private ImportEventHandler handler;

    /**
     * completed 事件：Handler 只做协议适配——幂等检查 → 事务外读 metadata → 委托
     * ImportPersistenceService.persistCompleted；缓存失效与落库编排位于 Service。
     */
    @Test
    void handleComicImported_delegatesPersistenceToService() throws Exception {
        ImportTask task = new ImportTask();
        task.setId(10L);
        task.setStatus(ImportTaskStatus.PARSING);
        Map<String, Object> metadata = Map.of(
                "comic", Map.of(),
                "catalogs", List.of(),
                "chapters", List.of());
        ImportTaskCompletedEvent event = new ImportTaskCompletedEvent(
                UUID.randomUUID(), Instant.now(), 10L, 20L, "metadata/10.json");

        when(redisTemplate.hasKey(any())).thenReturn(false);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(taskMapper.selectById(10L)).thenReturn(task);
        doReturn(metadata).when(objectMapper).readValue(any(File.class), any(TypeReference.class));
        when(importPersistenceService.persistCompleted(event, metadata)).thenReturn(List.of());
        ApiStorageRoot metadataRoot = new ApiStorageRoot();
        metadataRoot.setPath(Path.of("D:/manga/metadata"));
        when(storageProperties.root("METADATA")).thenReturn(metadataRoot);

        handler.handleComicImported(event, channel, 1L);

        verify(importPersistenceService).persistCompleted(event, metadata);
        verify(channel).basicAck(1L, false);
    }

    /** 让 transactionTemplate.executeWithoutResult 内联执行 Consumer，并在此"事务"中运行 action。 */
    private void runInTransaction(Runnable action) {
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<TransactionStatus> consumer = invocation.getArgument(0);
            consumer.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
        action.run();
    }

    /** 遗留 "DOWNLOADING" 行经 safeValueOf 读回 status=null：失败事件必须正常标记 FAILED，不得 NPE。 */
    @Test
    void handleImportTaskFailed_withLegacyNullStatusTask_marksFailedWithoutNpe() throws Exception {
        ImportTask task = new ImportTask();
        task.setId(30L);
        task.setStatus(null);

        when(taskMapper.selectById(30L)).thenReturn(task);

        ImportTaskFailedEvent event = new ImportTaskFailedEvent(
                UUID.randomUUID(), Instant.now(), 30L, 300L, "DOWNLOAD_FAILED", "下载失败");

        runInTransaction(() -> assertDoesNotThrow(() -> handler.handleImportTaskFailed(event, channel, 1L)));
        verify(channel).basicAck(1L, false);

        ArgumentCaptor<ImportTask> captor = ArgumentCaptor.forClass(ImportTask.class);
        verify(taskMapper).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(ImportTaskStatus.FAILED);
    }

    /** 遗留 status=null 行收到终态事件：正常写入终态，不得 NPE。 */
    @Test
    void handleTaskStatusChanged_withLegacyNullStatusTask_terminalEventWritesWithoutNpe() throws Exception {
        ImportTask task = new ImportTask();
        task.setId(31L);
        task.setStatus(null);

        when(taskMapper.selectById(31L)).thenReturn(task);

        TaskStatusChangedEvent event = new TaskStatusChangedEvent(
                UUID.randomUUID(), Instant.now(), 31L, "FAILED", 0, null, 0, 0, null);

        runInTransaction(() -> assertDoesNotThrow(() -> handler.handleTaskStatusChanged(event, channel, 1L)));
        verify(channel).basicAck(1L, false);

        ArgumentCaptor<ImportTask> captor = ArgumentCaptor.forClass(ImportTask.class);
        verify(taskMapper).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(ImportTaskStatus.FAILED);
    }

    /** 遗留 status=null 行收到阶段事件：不抛异常，status 保持 null（阶段仅写 management_task.stage）。 */
    @Test
    void handleTaskStatusChanged_withLegacyNullStatusTask_stageEventDoesNotThrow() throws Exception {
        ImportTask task = new ImportTask();
        task.setId(32L);
        task.setStatus(null);

        when(taskMapper.selectById(32L)).thenReturn(task);

        TaskStatusChangedEvent event = new TaskStatusChangedEvent(
                UUID.randomUUID(), Instant.now(), 32L, "DOWNLOADING", 42, "HTTP", 1024, 7, null);

        runInTransaction(() -> assertDoesNotThrow(() -> handler.handleTaskStatusChanged(event, channel, 1L)));
        verify(channel).basicAck(1L, false);

        ArgumentCaptor<ImportTask> captor = ArgumentCaptor.forClass(ImportTask.class);
        verify(taskMapper).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isNull();
    }

    /**
     * Worker 导入失败只发 TaskStatusChangedEvent(FAILED) 不发 ImportTaskFailedEvent：
     * comic 必须联动 IMPORT_FAILED，否则漫画永久卡 IMPORTING（taskId=207/comicId=239 复现）。
     */
    @Test
    void handleTaskStatusChanged_withFailedStatus_marksComicImportFailed() throws Exception {
        ImportTask task = new ImportTask();
        task.setId(33L);
        task.setComicId(40L);
        task.setStatus(ImportTaskStatus.PARSING);

        Comic comic = new Comic();
        comic.setId(40L);
        comic.setStatus(ComicStatus.IMPORTING);

        when(taskMapper.selectById(33L)).thenReturn(task);
        when(comicMapper.selectById(40L)).thenReturn(comic);

        TaskStatusChangedEvent event = new TaskStatusChangedEvent(
                UUID.randomUUID(), Instant.now(), 33L, "FAILED", 0, null, 0, 0, null);

        runInTransaction(() -> handler.handleTaskStatusChanged(event, channel, 1L));
        verify(channel).basicAck(1L, false);

        ArgumentCaptor<Comic> captor = ArgumentCaptor.forClass(Comic.class);
        verify(comicMapper).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(ComicStatus.IMPORT_FAILED);
    }

    /** 非 FAILED 状态（如 READY/阶段值）不得触发 comic 联动。 */
    @Test
    void handleTaskStatusChanged_withNonFailedStatus_doesNotTouchComic() throws Exception {
        ImportTask task = new ImportTask();
        task.setId(34L);
        task.setComicId(41L);
        task.setStatus(ImportTaskStatus.PARSING);

        when(taskMapper.selectById(34L)).thenReturn(task);

        TaskStatusChangedEvent event = new TaskStatusChangedEvent(
                UUID.randomUUID(), Instant.now(), 34L, "DOWNLOADING", 10, "HTTP", 0, 0, null);

        runInTransaction(() -> handler.handleTaskStatusChanged(event, channel, 1L));

        verify(comicMapper, never()).updateById(any(Comic.class));
    }

    /** Worker 失败事件携带 errorMessage：必须写入任务，供前端展示与重试决策。 */
    @Test
    void handleTaskStatusChanged_withFailedStatus_persistsErrorMessage() throws Exception {
        ImportTask task = new ImportTask();
        task.setId(35L);
        task.setStatus(ImportTaskStatus.PARSING);

        when(taskMapper.selectById(35L)).thenReturn(task);

        TaskStatusChangedEvent event = new TaskStatusChangedEvent(
                UUID.randomUUID(), Instant.now(), 35L, "FAILED", 0, null, 0, 0,
                "源文件缺失: D:/comics/ComicA/001.jpg");

        runInTransaction(() -> handler.handleTaskStatusChanged(event, channel, 1L));
        verify(channel).basicAck(1L, false);

        ArgumentCaptor<ImportTask> captor = ArgumentCaptor.forClass(ImportTask.class);
        verify(taskMapper).updateById(captor.capture());
        assertThat(captor.getValue().getErrorMessage()).isEqualTo("源文件缺失: D:/comics/ComicA/001.jpg");
    }
}
