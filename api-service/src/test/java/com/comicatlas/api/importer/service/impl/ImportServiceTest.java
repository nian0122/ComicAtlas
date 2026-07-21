package com.comicatlas.api.importer.service.impl;

import com.comicatlas.api.comic.entity.Comic;
import com.comicatlas.api.comic.mapper.CatalogMapper;
import com.comicatlas.api.comic.mapper.ChapterMapper;
import com.comicatlas.api.comic.mapper.ComicMapper;
import com.comicatlas.api.comic.mapper.MediaMapper;
import com.comicatlas.api.common.exception.BusinessException;
import com.comicatlas.api.importer.dto.BatchImportRequest;
import com.comicatlas.api.importer.dto.BatchImportResultVO;
import com.comicatlas.api.importer.entity.ImportTask;
import com.comicatlas.api.importer.event.ImportEventPublisher;
import com.comicatlas.api.importer.mapper.ImportTaskMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ImportServiceTest {

    @Mock private ImportTaskMapper taskMapper;
    @Mock private ComicMapper comicMapper;
    @Mock private CatalogMapper catalogMapper;
    @Mock private ChapterMapper chapterMapper;
    @Mock private MediaMapper mediaMapper;
    @Mock private ImportEventPublisher eventPublisher;
    @Mock private RedisTemplate<String, Object> redisTemplate;
    @Mock private TransactionTemplate transactionTemplate;
    @InjectMocks private ImportServiceImpl service;

    @BeforeEach
    void setUp() {
        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
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
        saved1.setSourceType("DIRECTORY");
        saved1.setSourcePath("D:/manga/test/comic1");
        saved1.setStatus("PENDING");

        ImportTask saved2 = new ImportTask();
        saved2.setId(201L);
        saved2.setComicId(101L);
        saved2.setSourceType("DIRECTORY");
        saved2.setSourcePath("D:/manga/test/comic2");
        saved2.setStatus("PENDING");

        when(taskMapper.selectById(200L)).thenReturn(saved1);
        when(taskMapper.selectById(201L)).thenReturn(saved2);

        BatchImportResultVO result = service.createBatchImportTasks(request);

        assertNotNull(result.getBatchId());
        assertEquals(2, result.getTotal());
        assertEquals(2, result.getSucceeded().size());
        assertEquals(0, result.getFailed().size());

        verify(eventPublisher, times(2)).publishImportTaskCreated(anyLong(), anyLong(), anyString(), anyString());
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
        saved.setSourceType("DIRECTORY");
        saved.setSourcePath("D:/manga/test/valid");
        saved.setStatus("PENDING");

        when(taskMapper.selectById(200L)).thenReturn(saved);

        BatchImportResultVO result = service.createBatchImportTasks(request);

        assertEquals(1, result.getSucceeded().size());
        assertEquals(1, result.getFailed().size());
        assertEquals("D:/manga/test/invalid", result.getFailed().get(0).getSourcePath());
        assertTrue(result.getFailed().get(0).getErrorMessage().contains("Path not found"));

        verify(eventPublisher, times(1)).publishImportTaskCreated(anyLong(), anyLong(), anyString(), anyString());
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

    // Test 5: parentPath does not exist
    @Test
    void scanDirectories_shouldThrow400_whenParentPathNotExists() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.scanDirectories("D:/nonexistent_deadbeef1234/path", "DIRECTORY"));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("不存在"));
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
        saved1.setStatus("PENDING");

        ImportTask saved2 = new ImportTask();
        saved2.setId(201L);
        saved2.setComicId(101L);
        saved2.setStatus("PENDING");

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
        saved.setStatus("PENDING");

        when(taskMapper.selectById(200L)).thenReturn(saved);

        service.createBatchImportTasks(request);

        ImportTask captured = taskCaptor.getValue();
        assertNotNull(captured.getBatchId());
    }
}
