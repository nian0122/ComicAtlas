package com.comicatlas.api.importer.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.comicatlas.api.catalog.cache.CatalogCacheInvalidator;
import com.comicatlas.api.importer.entity.ImportTask;
import com.comicatlas.api.importer.mapper.ImportTaskMapper;
import com.comicatlas.api.outbox.service.OutboxService;
import com.comicatlas.common.event.ImportTaskCreatedEvent;
import com.comicatlas.contract.common.enums.ComicStatus;
import com.comicatlas.api.importer.enums.ImportTaskStatus;
import com.comicatlas.contract.common.enums.SourceType;
import com.comicatlas.persistence.comic.entity.Chapter;
import com.comicatlas.persistence.comic.mapper.CatalogMapper;
import com.comicatlas.persistence.comic.mapper.ChapterMapper;
import com.comicatlas.persistence.comic.mapper.ComicMapper;
import com.comicatlas.persistence.comic.mapper.MediaMapper;
import com.comicatlas.api.storage.ApiStorageProperties;
import com.comicatlas.api.storage.ApiStorageRoot;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    private ImportRetryStorageService retryStorageService;
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
        retryStorageService = new ImportRetryStorageService(storageProperties);
        coordinator = new ImportRetryCoordinator(
                importTaskMapper, comicMapper, chapterMapper, mediaMapper, catalogMapper,
                catalogCacheInvalidator, outboxService, storageProperties, redisTemplate,
                retryStorageService);
        // 重试入队会注册事务提交后回调，统一初始化同步器（tearDown 负责清理）
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        try {
            TransactionSynchronizationManager.clearSynchronization();
        } catch (IllegalStateException ignored) {
        }
    }

    private static ImportTask failedTask(Long id, Long comicId, SourceType sourceType) {
        ImportTask task = new ImportTask();
        task.setId(id);
        task.setComicId(comicId);
        task.setStatus(ImportTaskStatus.FAILED);
        task.setRetryCount(0);
        task.setSourceType(sourceType);
        task.setSourcePath(sourceType == SourceType.EHENTAI ? null : "D:/manga/test/comic");
        return task;
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
        ImportTask task = failedTask(2L, 10L, SourceType.DIRECTORY);
        task.setErrorMessage("模拟失败");
        when(importTaskMapper.update(eq(null), any(Wrapper.class))).thenReturn(1);
        when(chapterMapper.selectList(any())).thenReturn(List.of());
        when(comicMapper.selectById(10L)).thenReturn(null);

        boolean retried = coordinator.retry(task);

        assertTrue(retried);
        assertEquals(ImportTaskStatus.PENDING, task.getStatus());
        assertEquals(1, task.getRetryCount());
        verify(importTaskMapper).update(eq(null), any(Wrapper.class));
        verify(outboxService).enqueue(any(ImportTaskCreatedEvent.class), eq("comic.import"), eq("task.created"));
    }

    @Test
    void retry_casConflict_skipsWhenConcurrentRetryWon() {
        ImportTask task = failedTask(2L, 10L, SourceType.DIRECTORY);
        when(importTaskMapper.update(eq(null), any(Wrapper.class))).thenReturn(0);

        boolean retried = coordinator.retry(task);

        assertFalse(retried);
        verify(outboxService, never()).enqueue(any(), any(), any());
        verify(chapterMapper, never()).selectList(any());
    }

    @Test
    void retry_marksComicImporting_whenImportFailed() {
        ImportTask task = failedTask(3L, 11L, SourceType.DIRECTORY);
        when(importTaskMapper.update(eq(null), any(Wrapper.class))).thenReturn(1);
        when(chapterMapper.selectList(any())).thenReturn(List.of());
        com.comicatlas.persistence.comic.entity.Comic comic = new com.comicatlas.persistence.comic.entity.Comic();
        comic.setId(11L);
        comic.setStatus(ComicStatus.IMPORT_FAILED);
        when(comicMapper.selectById(11L)).thenReturn(comic);

        coordinator.retry(task);

        verify(comicMapper).updateById(comic);
        assertTrue(comic.getStatus() == ComicStatus.IMPORTING);
    }

    @Test
    void retry_ehentai_fallsBackToSourceRef_whenSourcePathMissing() {
        ImportTask task = failedTask(5L, 12L, SourceType.EHENTAI);
        task.setSourceRef("https://e-hentai.org/g/12345");
        when(importTaskMapper.update(eq(null), any(Wrapper.class))).thenReturn(1);
        when(chapterMapper.selectList(any())).thenReturn(List.of());
        when(comicMapper.selectById(12L)).thenReturn(null);

        coordinator.retry(task);

        ArgumentCaptor<ImportTaskCreatedEvent> eventCaptor =
                ArgumentCaptor.forClass(ImportTaskCreatedEvent.class);
        verify(outboxService).enqueue(eventCaptor.capture(), eq("comic.import"), eq("task.created"));
        assertEquals("https://e-hentai.org/g/12345", eventCaptor.getValue().sourcePath());
    }

    @Test
    void retry_reverseFinalize_restoresStagingBeforeDeletingChapters() throws Exception {
        ImportTask task = failedTask(6L, 60L, SourceType.DIRECTORY);
        Chapter ch1 = new Chapter();
        ch1.setId(7001L);
        ch1.setComicId(60L);
        ch1.setGlobalOrder(5);
        when(importTaskMapper.update(eq(null), any(Wrapper.class))).thenReturn(1);
        when(chapterMapper.selectList(any())).thenReturn(List.of(ch1));
        when(comicMapper.selectById(60L)).thenReturn(null);

        Path chapterDir = Path.of("target/test-tmp/hq/60/7001");
        Files.createDirectories(chapterDir);
        Files.writeString(chapterDir.resolve("001.jpg"), "finalized");

        coordinator.retry(task);
        assertTrue(Files.exists(Path.of("target/test-tmp/hq/.staging/6/60/5/001.jpg")),
                "反最终化应把文件搬回当前任务隔离暂存目录");
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(sync -> sync.afterCommit());

        Path stagingFile = Path.of("target/test-tmp/hq/.staging/6/60/5/001.jpg");
        assertEquals("finalized", Files.readString(stagingFile));
        assertFalse(Files.exists(chapterDir), "搬空后旧章节目录应在提交后清理");
    }

    @Test
    void retry_reverseFinalize_keepsStagingVersion_whenSizeMatches() throws Exception {
        ImportTask task = failedTask(7L, 61L, SourceType.DIRECTORY);
        Chapter ch1 = new Chapter();
        ch1.setId(7002L);
        ch1.setComicId(61L);
        ch1.setGlobalOrder(6);
        when(importTaskMapper.update(eq(null), any(Wrapper.class))).thenReturn(1);
        when(chapterMapper.selectList(any())).thenReturn(List.of(ch1));
        when(comicMapper.selectById(61L)).thenReturn(null);

        Path stagingFile = Path.of("target/test-tmp/hq/.staging/7/61/6/001.jpg");
        Files.createDirectories(stagingFile.getParent());
        Files.writeString(stagingFile, "staging");
        Path chapterDir = Path.of("target/test-tmp/hq/61/7002");
        Files.createDirectories(chapterDir);
        Files.writeString(chapterDir.resolve("001.jpg"), "chapter");

        coordinator.retry(task);

        assertEquals("staging", Files.readString(stagingFile), "暂存已有同大小文件时保留暂存版本");
        assertFalse(Files.exists(chapterDir.resolve("001.jpg")), "章节目录副本应被去重删除");
    }

    @Test
    void retry_rebuildsManifest_whenComicMetadataExists() throws Exception {
        ImportTask task = failedTask(8L, 70L, SourceType.DIRECTORY);
        when(importTaskMapper.update(eq(null), any(Wrapper.class))).thenReturn(1);
        when(chapterMapper.selectList(any())).thenReturn(List.of());
        when(comicMapper.selectById(70L)).thenReturn(null);

        // persist 已发生：写出完整漫画元数据（staging 布局 hqPath）与两个暂存章节文件
        Path metadataDir = Path.of("target/test-tmp/metadata");
        Files.createDirectories(metadataDir);
        Files.writeString(metadataDir.resolve("70.json"),
                "{\"version\":3,\"comic\":{\"title\":\"Test\"},\"catalogs\":[],\"chapters\":["
                        + "{\"globalOrder\":1,\"mediaItems\":[{\"fileName\":\"001.jpg\",\"hqPath\":\"70/1/001.jpg\"}]},"
                        + "{\"globalOrder\":2,\"mediaItems\":[{\"fileName\":\"001.jpg\",\"hqPath\":\"70/2/001.jpg\"}]}]}");
        Files.createDirectories(Path.of("target/test-tmp/hq/.staging/8/70/1"));
        Files.writeString(Path.of("target/test-tmp/hq/.staging/8/70/1/001.jpg"), "a");
        Files.createDirectories(Path.of("target/test-tmp/hq/.staging/8/70/2"));
        Files.writeString(Path.of("target/test-tmp/hq/.staging/8/70/2/001.jpg"), "b");

        coordinator.retry(task);

        Path manifest = Path.of("target/test-tmp/imports/8/manifest.json");
        assertTrue(Files.exists(manifest), "persist 已发生时重试应重建完整清单");
        JsonNode node = new ObjectMapper().readTree(manifest.toFile());
        assertEquals(1, node.get("version").asInt());
        assertEquals(2, node.get("files").size(), "重建清单应包含全部暂存章节文件");
        assertEquals(".staging/8/70/1/001.jpg", node.get("files").get(0).get("target").asText());
    }

    @Test
    void retry_keepsOriginalManifest_whenNoComicMetadata() {
        ImportTask task = failedTask(9L, 71L, SourceType.DIRECTORY);
        when(importTaskMapper.update(eq(null), any(Wrapper.class))).thenReturn(1);
        when(chapterMapper.selectList(any())).thenReturn(List.of());
        when(comicMapper.selectById(71L)).thenReturn(null);

        coordinator.retry(task);

        assertFalse(Files.exists(Path.of("target/test-tmp/imports/9/manifest.json")),
                "persist 未发生时不应重建清单（原清单完整，保留恢复点）");
    }

    @Test
    void retry_cleansOrphanHqChapterDirs_afterCommit() throws Exception {
        ImportTask task = failedTask(4L, 60L, SourceType.DIRECTORY);
        Chapter ch1 = new Chapter();
        ch1.setId(7001L);
        ch1.setComicId(60L);
        when(importTaskMapper.update(eq(null), any(Wrapper.class))).thenReturn(1);
        when(chapterMapper.selectList(any())).thenReturn(List.of(ch1));
        when(comicMapper.selectById(60L)).thenReturn(null);

        Path orphanDir = Path.of("target/test-tmp/hq/60/7001");
        Files.createDirectories(orphanDir);
        Files.writeString(orphanDir.resolve("001.jpg"), "orphan");

        coordinator.retry(task);
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(sync -> sync.afterCommit());

        assertFalse(Files.exists(orphanDir), "重试提交后旧 chapterId 目录应被清理");
    }
}
