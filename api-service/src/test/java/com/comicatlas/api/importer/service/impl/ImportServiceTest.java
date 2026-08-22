package com.comicatlas.api.importer.service.impl;

import com.comicatlas.persistence.comic.entity.Comic;
import com.comicatlas.api.catalog.cache.CatalogCacheInvalidator;
import com.comicatlas.persistence.comic.mapper.CatalogMapper;
import com.comicatlas.persistence.comic.mapper.ChapterMapper;
import com.comicatlas.persistence.comic.mapper.ComicMapper;
import com.comicatlas.persistence.comic.mapper.MediaMapper;
import com.comicatlas.api.importer.enums.ImportTaskStatus;
import com.comicatlas.contract.common.enums.SourceType;
import com.comicatlas.contract.common.exception.BusinessException;
import com.comicatlas.api.storage.ApiStorageProperties;
import com.comicatlas.api.storage.ApiStorageRoot;
import com.comicatlas.api.importer.dto.BatchImportRequest;
import com.comicatlas.api.importer.dto.BatchImportResultVO;
import com.comicatlas.api.importer.entity.ImportTask;
import com.comicatlas.api.importer.mapper.ImportTaskMapper;
import com.comicatlas.api.importer.service.ImportRetryCoordinator;
import com.comicatlas.api.task.dto.ManagementTaskResponse;
import com.comicatlas.api.task.service.ManagementTaskService;
import com.comicatlas.api.outbox.service.OutboxService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


@ExtendWith(MockitoExtension.class)
class ImportServiceTest {

    @Mock private ImportTaskMapper taskMapper;
    @Mock private ComicMapper comicMapper;
    @Mock private CatalogMapper catalogMapper;
    @Mock private ChapterMapper chapterMapper;
    @Mock private MediaMapper mediaMapper;
    @Mock private RedisTemplate<String, Object> redisTemplate;
    @Mock private TransactionTemplate transactionTemplate;
    @Mock private CatalogCacheInvalidator catalogCacheInvalidator;
    @Mock private ManagementTaskService managementTaskService;
    @Mock private OutboxService outboxService;
    @Mock private ApiStorageProperties storageProperties;
    @Mock private ImportRetryCoordinator importRetryCoordinator;
    @InjectMocks private ImportServiceImpl service;

    @BeforeEach
    void setUp() {
        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        AtomicLong mgmtIdGen = new AtomicLong(900);
        lenient().when(managementTaskService.createTask(any(), any(), any())).thenAnswer(invocation -> {
            ManagementTaskResponse resp = new ManagementTaskResponse();
            resp.setId(mgmtIdGen.getAndIncrement());
            return resp;
        });
        ApiStorageRoot metadataRoot = new ApiStorageRoot();
        metadataRoot.setPath(Path.of("target/test-tmp/metadata"));
        lenient().when(storageProperties.root("METADATA")).thenReturn(metadataRoot);
        ApiStorageRoot hqRoot = new ApiStorageRoot();
        hqRoot.setPath(Path.of("target/test-tmp/hq"));
        lenient().when(storageProperties.root("HQ")).thenReturn(hqRoot);
    }

    @AfterEach
    void tearDownSync() {
        try {
            TransactionSynchronizationManager.clearSynchronization();
        } catch (IllegalStateException ignored) {
            // 部分测试未初始化同步管理器
        }
    }

    // Test 1: normal batch with 2 paths
    @Test
    void createBatchImportTasks_shouldSucceedForAllPaths() {
        BatchImportRequest request = new BatchImportRequest();
        request.setSourceType("DIRECTORY");
        request.setSourcePaths(List.of("D:/manga/test/comic1", "D:/manga/test/comic2"));

        AtomicLong comicIdGen = new AtomicLong(100);
        doAnswer(inv -> {
            Comic c = inv.getArgument(0);
            c.setId(comicIdGen.getAndIncrement());
            return 1;
        }).when(comicMapper).insert(any(Comic.class));

        AtomicLong taskIdGen = new AtomicLong(200);
        doAnswer(inv -> {
            ImportTask t = inv.getArgument(0);
            t.setId(taskIdGen.getAndIncrement());
            return 1;
        }).when(taskMapper).insert(any(ImportTask.class));

        ImportTask saved1 = new ImportTask();
        saved1.setId(200L);
        saved1.setComicId(100L);
        saved1.setSourceType(SourceType.DIRECTORY);
        saved1.setSourcePath("D:/manga/test/comic1");
        saved1.setStatus(ImportTaskStatus.PENDING);

        ImportTask saved2 = new ImportTask();
        saved2.setId(201L);
        saved2.setComicId(101L);
        saved2.setSourceType(SourceType.DIRECTORY);
        saved2.setSourcePath("D:/manga/test/comic2");
        saved2.setStatus(ImportTaskStatus.PENDING);

        when(taskMapper.selectById(200L)).thenReturn(saved1);
        when(taskMapper.selectById(201L)).thenReturn(saved2);

        BatchImportResultVO result = service.createBatchImportTasks(request);

        assertNotNull(result.getBatchId());
        assertEquals(2, result.getTotal());
        assertEquals(2, result.getSucceeded().size());
        assertEquals(0, result.getFailed().size());

        verify(outboxService, times(2)).enqueue(any(), eq("comic.import"), eq("task.created"));
    }

    // Test 2: partial failure
    @Test
    void createBatchImportTasks_shouldHandlePartialFailure() {
        BatchImportRequest request = new BatchImportRequest();
        request.setSourceType("DIRECTORY");
        request.setSourcePaths(List.of("D:/manga/test/valid", "D:/manga/test/invalid"));

        doAnswer(inv -> {
            Comic c = inv.getArgument(0);
            c.setId(100L);
            return 1;
        }).doThrow(new RuntimeException("Path not found")).when(comicMapper).insert(any(Comic.class));

        doAnswer(inv -> {
            ImportTask t = inv.getArgument(0);
            t.setId(200L);
            return 1;
        }).when(taskMapper).insert(any(ImportTask.class));

        ImportTask saved = new ImportTask();
        saved.setId(200L);
        saved.setComicId(100L);
        saved.setSourceType(SourceType.DIRECTORY);
        saved.setSourcePath("D:/manga/test/valid");
        saved.setStatus(ImportTaskStatus.PENDING);

        when(taskMapper.selectById(200L)).thenReturn(saved);

        BatchImportResultVO result = service.createBatchImportTasks(request);

        assertEquals(1, result.getSucceeded().size());
        assertEquals(1, result.getFailed().size());
        assertEquals("D:/manga/test/invalid", result.getFailed().get(0).getSourcePath());
        assertTrue(result.getFailed().get(0).getErrorMessage().contains("Path not found"));

        verify(outboxService, times(1)).enqueue(any(), eq("comic.import"), eq("task.created"));
    }

    // Test 3: empty sourcePaths
    @Test
    void createBatchImportTasks_shouldThrow400_whenSourcePathsEmpty() {
        BatchImportRequest request = new BatchImportRequest();
        request.setSourcePaths(Collections.emptyList());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createBatchImportTasks(request));
        assertEquals(400, ex.getCode());
    }

    // Test 4: null sourcePaths
    @Test
    void createBatchImportTasks_shouldThrow400_whenSourcePathsNull() {
        BatchImportRequest request = new BatchImportRequest();
        request.setSourcePaths(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createBatchImportTasks(request));
        assertEquals(400, ex.getCode());
    }

    // Test 6: batchId consistency
    @Test
    void createBatchImportTasks_shouldAllHaveSameBatchId() {
        BatchImportRequest request = new BatchImportRequest();
        request.setSourceType("DIRECTORY");
        request.setSourcePaths(List.of("D:/manga/test/comicA", "D:/manga/test/comicB"));

        AtomicLong comicIdGen = new AtomicLong(100);
        doAnswer(inv -> {
            Comic c = inv.getArgument(0);
            c.setId(comicIdGen.getAndIncrement());
            return 1;
        }).when(comicMapper).insert(any(Comic.class));

        ArgumentCaptor<ImportTask> taskCaptor = ArgumentCaptor.forClass(ImportTask.class);
        AtomicLong taskIdGen = new AtomicLong(200);
        doAnswer(inv -> {
            ImportTask t = inv.getArgument(0);
            t.setId(taskIdGen.getAndIncrement());
            return 1;
        }).when(taskMapper).insert(taskCaptor.capture());

        ImportTask saved1 = new ImportTask();
        saved1.setId(200L);
        saved1.setComicId(100L);
        saved1.setStatus(ImportTaskStatus.PENDING);

        ImportTask saved2 = new ImportTask();
        saved2.setId(201L);
        saved2.setComicId(101L);
        saved2.setStatus(ImportTaskStatus.PENDING);

        when(taskMapper.selectById(200L)).thenReturn(saved1);
        when(taskMapper.selectById(201L)).thenReturn(saved2);

        BatchImportResultVO result = service.createBatchImportTasks(request);

        List<ImportTask> captured = taskCaptor.getAllValues();
        assertEquals(2, captured.size());

        String batchId = captured.get(0).getBatchId();
        assertNotNull(batchId);
        assertEquals(batchId, captured.get(1).getBatchId());
        assertEquals(batchId, result.getBatchId());
    }

    // Test 7: sets batchId on tasks
    @Test
    void createBatchImportTasks_shouldSetBatchIdOnTasks() {
        BatchImportRequest request = new BatchImportRequest();
        request.setSourceType("DIRECTORY");
        request.setSourcePaths(List.of("D:/manga/test/single"));

        doAnswer(inv -> {
            Comic c = inv.getArgument(0);
            c.setId(100L);
            return 1;
        }).when(comicMapper).insert(any(Comic.class));

        ArgumentCaptor<ImportTask> taskCaptor = ArgumentCaptor.forClass(ImportTask.class);
        doAnswer(inv -> {
            ImportTask t = inv.getArgument(0);
            t.setId(200L);
            return 1;
        }).when(taskMapper).insert(taskCaptor.capture());

        ImportTask saved = new ImportTask();
        saved.setId(200L);
        saved.setComicId(100L);
        saved.setStatus(ImportTaskStatus.PENDING);

        when(taskMapper.selectById(200L)).thenReturn(saved);

        service.createBatchImportTasks(request);

        ImportTask captured = taskCaptor.getValue();
        assertNotNull(captured.getBatchId());
    }

    // Test: cancelTask 写 Redis 取消标记
    @Test
    void cancelTask_writesRedisCancelKey() {
        ImportTask t = new ImportTask();
        t.setId(300L);
        t.setComicId(100L);
        t.setStatus(ImportTaskStatus.PENDING);
        when(taskMapper.selectById(300L)).thenReturn(t);

        @SuppressWarnings("unchecked")
        ValueOperations<String, Object> ops =
                mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(ops);

        TransactionSynchronizationManager.initSynchronization();
        service.cancelTask(300L);
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(sync -> sync.afterCommit());

        verify(ops).set(eq("import:cancel:300"), eq("1"), any(Duration.class));
        verify(outboxService).enqueue(any(), eq("comic.task"), eq("cancel.requested"));
    }

    // Test: retryTask 委托 ImportRetryCoordinator 完成清理与重新入队，并同步统一任务
    @Test
    void retryTask_delegatesToCoordinator_andSyncsManagementTask() {
        ImportTask t = new ImportTask();
        t.setId(301L);
        t.setComicId(100L);
        t.setStatus(ImportTaskStatus.FAILED);
        t.setSourceType(SourceType.DIRECTORY);
        t.setSourcePath("D:/manga/test/comic");
        t.setRetryCount(0);
        t.setManagementTaskId(207L);
        when(taskMapper.selectById(301L)).thenReturn(t);

        service.retryTask(301L);

        verify(importRetryCoordinator).retry(t);
        verify(managementTaskService).retryTask(207L);
    }

    @Test
    void retryTask_delegatesToCoordinator_whenNoManagementTask() {
        ImportTask task = new ImportTask();
        task.setId(10L);
        task.setComicId(20L);
        task.setStatus(ImportTaskStatus.FAILED);
        task.setRetryCount(0);
        task.setSourceType(SourceType.DIRECTORY);
        task.setSourcePath("D:/manga/test/retry");
        when(taskMapper.selectById(10L)).thenReturn(task);

        service.retryTask(10L);

        verify(importRetryCoordinator).retry(task);
        verify(managementTaskService, org.mockito.Mockito.never())
                .retryTask(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void retryTask_rejectsNonTerminalStatus() {
        ImportTask task = new ImportTask();
        task.setId(50L);
        task.setStatus(ImportTaskStatus.PENDING);
        when(taskMapper.selectById(50L)).thenReturn(task);

        assertThrows(BusinessException.class, () -> service.retryTask(50L));

        verify(importRetryCoordinator, org.mockito.Mockito.never()).retry(any());
    }
}
