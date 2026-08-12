package com.comicatlas.api.importer.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.comicatlas.api.comic.cache.CatalogCacheInvalidator;
import com.comicatlas.persistence.comic.entity.Chapter;
import com.comicatlas.persistence.comic.mapper.CatalogMapper;
import com.comicatlas.persistence.comic.mapper.ChapterMapper;
import com.comicatlas.persistence.comic.mapper.ComicMapper;
import com.comicatlas.persistence.comic.mapper.MediaMapper;
import com.comicatlas.contract.common.enums.ComicStatus;
import com.comicatlas.contract.common.enums.ImportTaskStatus;
import com.comicatlas.contract.common.enums.SourceType;
import com.comicatlas.persistence.storage.ApiStorageProperties;
import com.comicatlas.persistence.storage.ApiStorageRoot;
import com.comicatlas.api.importer.entity.ImportTask;
import com.comicatlas.api.importer.mapper.ImportTaskMapper;
import com.comicatlas.api.outbox.service.OutboxService;
import com.comicatlas.common.event.ImportTaskCreatedEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ImportRetryCoordinatorTest {

    @Mock private ImportTaskMapper importTaskMapper;
    @Mock private ComicMapper comicMapper;
    @Mock private ChapterMapper chapterMapper;
    @Mock private MediaMapper mediaMapper;
    @Mock private CatalogMapper catalogMapper;
    @Mock private CatalogCacheInvalidator catalogCacheInvalidator;
    @Mock private OutboxService outboxService;
    @Mock private RedisTemplate<String, Object> redisTemplate;
    private ApiStorageProperties storageProperties;

    private ImportRetryCoordinator coordinator;

    @BeforeEach
    void setUp() {
        ApiStorageRoot metadataRoot = new ApiStorageRoot();
        metadataRoot.setPath(Path.of("target/test-tmp/metadata"));
        ApiStorageRoot hqRoot = new ApiStorageRoot();
        hqRoot.setPath(Path.of("target/test-tmp/hq"));
        storageProperties = new ApiStorageProperties();
        storageProperties.setRoots(java.util.Map.of("METADATA", metadataRoot, "HQ", hqRoot));
        coordinator = new ImportRetryCoordinator(
                importTaskMapper, comicMapper, chapterMapper, mediaMapper, catalogMapper,
                catalogCacheInvalidator, outboxService, storageProperties, redisTemplate);
    }

    @AfterEach
    void tearDown() {
        try {
            TransactionSynchronizationManager.clearSynchronization();
        } catch (IllegalStateException ignored) {
        }
    }

    @Test
    void retry_skipsWhenNotTerminal() {
        ImportTask task = new ImportTask();
        task.setId(1L);
        task.setStatus(ImportTaskStatus.PENDING);

        boolean retried = coordinator.retry(task);

        assertFalse(retried);
        verify(importTaskMapper, never()).update(any(), any());
        verify(outboxService, never()).enqueue(any(), any(), any());
    }

    @Test
    void retry_resetsTaskAndRepublishesEvent() {
        ImportTask task = new ImportTask();
        task.setId(2L);
        task.setComicId(10L);
        task.setStatus(ImportTaskStatus.FAILED);
        task.setErrorMessage("模拟失败");
        task.setRetryCount(0);
        task.setSourceType(SourceType.DIRECTORY);
        task.setSourcePath("D:/manga/test/comic");
        when(chapterMapper.selectList(any())).thenReturn(List.of());
        when(comicMapper.selectById(10L)).thenReturn(null);

        TransactionSynchronizationManager.initSynchronization();
        boolean retried = coordinator.retry(task);

        assertTrue(retried);
        verify(importTaskMapper).update(eq(null), any(Wrapper.class));
        verify(outboxService).enqueue(any(ImportTaskCreatedEvent.class), eq("comic.import"), eq("task.created"));
    }

    @Test
    void retry_marksComicImporting_whenImportFailed() {
        ImportTask task = new ImportTask();
        task.setId(3L);
        task.setComicId(11L);
        task.setStatus(ImportTaskStatus.FAILED);
        task.setRetryCount(0);
        task.setSourceType(SourceType.DIRECTORY);
        task.setSourcePath("D:/manga/test/comic");
        when(chapterMapper.selectList(any())).thenReturn(List.of());
        com.comicatlas.persistence.comic.entity.Comic comic = new com.comicatlas.persistence.comic.entity.Comic();
        comic.setId(11L);
        comic.setStatus(ComicStatus.IMPORT_FAILED);
        when(comicMapper.selectById(11L)).thenReturn(comic);

        TransactionSynchronizationManager.initSynchronization();
        coordinator.retry(task);

        verify(comicMapper).updateById(comic);
        assertTrue(comic.getStatus() == ComicStatus.IMPORTING);
    }

    @Test
    void retry_cleansOrphanHqChapterDirs_afterCommit() throws Exception {
        ImportTask task = new ImportTask();
        task.setId(4L);
        task.setComicId(60L);
        task.setStatus(ImportTaskStatus.FAILED);
        task.setRetryCount(0);
        task.setSourceType(SourceType.DIRECTORY);
        task.setSourcePath("D:/manga/test/orphan");
        Chapter ch1 = new Chapter();
        ch1.setId(7001L);
        ch1.setComicId(60L);
        when(chapterMapper.selectList(any())).thenReturn(List.of(ch1));
        when(comicMapper.selectById(60L)).thenReturn(null);

        Path orphanDir = Path.of("target/test-tmp/hq/60/7001");
        Files.createDirectories(orphanDir);
        Files.writeString(orphanDir.resolve("001.jpg"), "orphan");

        TransactionSynchronizationManager.initSynchronization();
        try {
            coordinator.retry(task);
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(sync -> sync.afterCommit());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        assertFalse(Files.exists(orphanDir), "重试提交后旧 chapterId 目录应被清理");
    }
}
