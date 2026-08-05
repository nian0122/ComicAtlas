package com.comicatlas.api.importer.event;

import com.comicatlas.api.comic.cache.CatalogCacheInvalidator;
import com.comicatlas.api.comic.entity.Comic;
import com.comicatlas.api.comic.mapper.CatalogMapper;
import com.comicatlas.api.comic.mapper.ChapterMapper;
import com.comicatlas.api.comic.mapper.ComicMapper;
import com.comicatlas.api.comic.mapper.MediaMapper;
import com.comicatlas.api.importer.entity.ImportTask;
import com.comicatlas.api.importer.mapper.ImportTaskMapper;
import com.comicatlas.api.common.enums.ImportTaskStatus;
import com.comicatlas.api.common.storage.ApiStorageProperties;
import com.comicatlas.api.common.storage.ApiStorageRoot;
import com.comicatlas.api.management.service.ManagementTaskService;
import com.comicatlas.common.event.ImportTaskCompletedEvent;
import com.comicatlas.common.event.ImportTaskFailedEvent;
import com.comicatlas.common.event.TaskStatusChangedEvent;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ImportEventHandlerCacheTest {

    @Mock private ObjectMapper objectMapper;
    @Mock private RedisTemplate<String, Object> redisTemplate;
    @Mock private ValueOperations<String, Object> valueOperations;
    @Mock private ComicMapper comicMapper;
    @Mock private CatalogMapper catalogMapper;
    @Mock private ChapterMapper chapterMapper;
    @Mock private MediaMapper mediaMapper;
    @Mock private ImportTaskMapper taskMapper;
    @Mock private TransactionTemplate transactionTemplate;
    @Mock private CatalogCacheInvalidator catalogCacheInvalidator;
    @Mock private ManagementTaskService managementTaskService;
    @Mock private ApiStorageProperties storageProperties;
    @Mock private Channel channel;
    @InjectMocks private ImportEventHandler handler;

    @Test
    void handleComicImported_shouldEvictCatalogCache_whenImportCompletes() throws Exception {
        ImportTask task = new ImportTask();
        task.setId(10L);
        task.setStatus(ImportTaskStatus.PARSING);
        Comic comic = new Comic();
        comic.setId(20L);
        Map<String, Object> metadata = Map.of(
                "comic", Map.of(),
                "catalogs", List.of(),
                "chapters", List.of());
        ImportTaskCompletedEvent event = new ImportTaskCompletedEvent(
                UUID.randomUUID(), Instant.now(), 10L, 20L, "metadata/10.json");

        when(redisTemplate.hasKey(any())).thenReturn(false);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(taskMapper.selectById(10L)).thenReturn(task);
        when(comicMapper.selectById(20L)).thenReturn(comic);
        doReturn(metadata).when(objectMapper).readValue(any(File.class), any(TypeReference.class));
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        when(managementTaskService.findActiveItem(any(), any(), any())).thenReturn(null);
        ApiStorageRoot metadataRoot = new ApiStorageRoot();
        metadataRoot.setPath(Path.of("D:/manga/metadata"));
        when(storageProperties.root("METADATA")).thenReturn(metadataRoot);

        handler.handleComicImported(event, channel, 1L);

        verify(catalogCacheInvalidator).evict(20L);
        verify(channel).basicAck(1L, false);
    }

    /** 遗留 "DOWNLOADING" 行经 safeValueOf 读回 status=null：失败事件必须正常标记 FAILED，不得 NPE。 */
    @Test
    void handleImportTaskFailed_withLegacyNullStatusTask_marksFailedWithoutNpe() throws Exception {
        ImportTask task = new ImportTask();
        task.setId(30L);
        task.setStatus(null);

        when(taskMapper.selectById(30L)).thenReturn(task);
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<TransactionStatus> action = invocation.getArgument(0);
            action.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        ImportTaskFailedEvent event = new ImportTaskFailedEvent(
                UUID.randomUUID(), Instant.now(), 30L, 300L, "DOWNLOAD_FAILED", "下载失败");

        assertDoesNotThrow(() -> handler.handleImportTaskFailed(event, channel, 1L));
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
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<TransactionStatus> action = invocation.getArgument(0);
            action.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        TaskStatusChangedEvent event = new TaskStatusChangedEvent(
                UUID.randomUUID(), Instant.now(), 31L, "FAILED", 0, null, 0, 0);

        assertDoesNotThrow(() -> handler.handleTaskStatusChanged(event, channel, 1L));
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
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<TransactionStatus> action = invocation.getArgument(0);
            action.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        TaskStatusChangedEvent event = new TaskStatusChangedEvent(
                UUID.randomUUID(), Instant.now(), 32L, "DOWNLOADING", 42, "HTTP", 1024, 7);

        assertDoesNotThrow(() -> handler.handleTaskStatusChanged(event, channel, 1L));
        verify(channel).basicAck(1L, false);
    }
}
