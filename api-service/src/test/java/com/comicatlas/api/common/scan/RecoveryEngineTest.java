package com.comicatlas.api.common.scan;

import com.comicatlas.api.admin.dto.RecoveryProgressVO;
import com.comicatlas.api.comic.cache.CatalogCacheInvalidator;
import com.comicatlas.persistence.comic.entity.Comic;
import com.comicatlas.persistence.comic.mapper.CatalogMapper;
import com.comicatlas.persistence.comic.mapper.ChapterMapper;
import com.comicatlas.persistence.comic.mapper.ComicMapper;
import com.comicatlas.persistence.comic.mapper.MediaMapper;
import com.comicatlas.persistence.storage.ApiStorageProperties;
import com.comicatlas.persistence.storage.ApiStorageRoot;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


@ExtendWith(MockitoExtension.class)
class RecoveryEngineTest {

    @Mock
    private ObjectMapper objectMapper;
    @Mock
    private ComicMapper comicMapper;
    @Mock
    private CatalogMapper catalogMapper;
    @Mock
    private ChapterMapper chapterMapper;
    @Mock
    private MediaMapper mediaMapper;
    @Mock
    private TransactionTemplate transactionTemplate;
    @Mock
    private CatalogCacheInvalidator catalogCacheInvalidator;
    @Mock
    private ApiStorageProperties storageProperties;

    @TempDir
    Path tempDir;

    private RecoveryEngine recoveryEngine;
    /** 真实 resolver，仅依赖被 mock 的 storageProperties（HQ 根指向临时目录）。 */
    private RecoveryMediaResolver realResolver;

    @BeforeEach
    void setUp() {
        ApiStorageRoot metadataRoot = new ApiStorageRoot();
        metadataRoot.setPath(Path.of("D:/manga/metadata"));
        ApiStorageRoot hqRoot = new ApiStorageRoot();
        hqRoot.setPath(tempDir.resolve("hq"));
        lenient().when(storageProperties.root("METADATA")).thenReturn(metadataRoot);
        lenient().when(storageProperties.root("HQ")).thenReturn(hqRoot);

        realResolver = new RecoveryMediaResolver(storageProperties);
        recoveryEngine = new RecoveryEngine(
                objectMapper, comicMapper, catalogMapper, chapterMapper, mediaMapper,
                transactionTemplate, catalogCacheInvalidator, storageProperties, realResolver);
    }

    // ======================== Comic 已存在 ========================

    @Test
    void processComicDir_shouldReturnSkipped_whenComicExistsInDb() {
        Comic existing = new Comic();
        existing.setId(1L);
        existing.setTitle("Test");
        when(comicMapper.selectById(1L)).thenReturn(existing);

        RecoveryProgressVO result = recoveryEngine.processComicDir(1L, 5);

        assertEquals(6, result.totalComics());
        assertEquals(0, result.recoveredComics());
        assertEquals(1, result.skippedComics());
        assertEquals(0, result.placeholderComics());
        assertEquals(0, result.errorComics());
        assertNull(result.lastError());
        assertEquals(0, result.restoredChapters());
        assertEquals(0, result.restoredPages());
    }

    // ======================== Metadata JSON 存在且有效 ========================

    @Test
    void processComicDir_shouldReturnRecovered_whenMetadataExists() throws Exception {
        when(comicMapper.selectById(2L)).thenReturn(null);

        Map<String, Object> metadata = Map.of(
            "comic", Map.of("title", "Test Comic", "author", "Author"),
            "catalogs", java.util.Collections.emptyList(),
            "chapters", java.util.Collections.emptyList()
        );

        try (MockedStatic<Files> filesMock = mockStatic(Files.class)) {
            filesMock.when(() -> Files.exists(any(Path.class))).thenReturn(true);

            when(objectMapper.readValue(any(java.io.File.class), any(TypeReference.class)))
                .thenReturn(metadata);
            when(transactionTemplate.execute(any()))
                .thenReturn(Map.of("catalogs", 2, "chapters", 3, "pages", 30));

            RecoveryProgressVO result = recoveryEngine.processComicDir(2L, 0);

            assertEquals(1, result.totalComics());
            assertEquals(1, result.recoveredComics());
            assertEquals(0, result.skippedComics());
            assertEquals(0, result.placeholderComics());
            assertEquals(0, result.errorComics());
            assertNull(result.lastError());
            assertEquals(3, result.restoredChapters());
            assertEquals(30, result.restoredPages());
        }
    }

    // ======================== Metadata JSON 缺失 ========================

    @Test
    void processComicDir_shouldReturnPlaceholder_whenMetadataMissing() {
        when(comicMapper.selectById(3L)).thenReturn(null);

        try (MockedStatic<Files> filesMock = mockStatic(Files.class)) {
            filesMock.when(() -> Files.exists(any(Path.class))).thenReturn(false);

            doNothing().when(transactionTemplate).executeWithoutResult(any());

            RecoveryProgressVO result = recoveryEngine.processComicDir(3L, 10);

            assertEquals(11, result.totalComics());
            assertEquals(0, result.recoveredComics());
            assertEquals(0, result.skippedComics());
            assertEquals(1, result.placeholderComics());
            assertEquals(0, result.errorComics());
            assertNull(result.lastError());
            assertEquals(0, result.restoredChapters());
            assertEquals(0, result.restoredPages());
        }
    }

    // ======================== 损坏的 metadata ========================

    @Test
    void processComicDir_shouldReturnError_whenMetadataIsBroken() throws Exception {
        when(comicMapper.selectById(4L)).thenReturn(null);

        try (MockedStatic<Files> filesMock = mockStatic(Files.class)) {
            filesMock.when(() -> Files.exists(any(Path.class))).thenReturn(true);

            when(objectMapper.readValue(any(java.io.File.class), any(TypeReference.class)))
                .thenThrow(new RuntimeException("JSON 解析失败"));

            RecoveryProgressVO result = recoveryEngine.processComicDir(4L, 0);

            assertEquals(1, result.totalComics());
            assertEquals(0, result.recoveredComics());
            assertEquals(0, result.skippedComics());
            assertEquals(0, result.placeholderComics());
            assertEquals(1, result.errorComics());
            assertTrue(result.lastError().contains("JSON 解析失败"));
            assertEquals(0, result.restoredChapters());
            assertEquals(0, result.restoredPages());
        }
    }

    // ======================== 占位创建失败 ========================

    @Test
    void processComicDir_shouldReturnError_whenPlaceholderCreationFails() {
        when(comicMapper.selectById(5L)).thenReturn(null);

        try (MockedStatic<Files> filesMock = mockStatic(Files.class)) {
            filesMock.when(() -> Files.exists(any(Path.class))).thenReturn(false);

            doThrow(new RuntimeException("DB 写入失败"))
                .when(transactionTemplate).executeWithoutResult(any());

            RecoveryProgressVO result = recoveryEngine.processComicDir(5L, 3);

            assertEquals(4, result.totalComics());
            assertEquals(0, result.recoveredComics());
            assertEquals(0, result.skippedComics());
            assertEquals(0, result.placeholderComics());
            assertEquals(1, result.errorComics());
            assertTrue(result.lastError().contains("DB 写入失败"));
        }
    }

    // ======================== 幂等性：重复调用返回一致结果 ========================

    @Test
    void processComicDir_shouldBeIdempotent_forSameComicId() {
        Comic existing = new Comic();
        existing.setId(6L);
        when(comicMapper.selectById(6L)).thenReturn(existing);

        RecoveryProgressVO first = recoveryEngine.processComicDir(6L, 0);
        RecoveryProgressVO second = recoveryEngine.processComicDir(6L, 0);

        assertEquals(1, first.skippedComics());
        assertEquals(1, second.skippedComics());
        assertEquals(first.totalComics(), second.totalComics());
    }

    // ======================== 现代 hqPath 解析（resolver） ========================

    @Test
    void resolveMedia_shouldUseMetadataHqPath_withoutRewritingToNewChapterId() throws Exception {
        // 现代 metadata：hqPath 指向旧 chapterId 目录（99），恢复后 DB 新 chapterId 不必相同
        Long comicId = 7700001L;
        Path hqDir = tempDir.resolve("hq").resolve(String.valueOf(comicId)).resolve("99");
        Files.createDirectories(hqDir);
        Files.writeString(hqDir.resolve("001.jpg"), "fake-jpeg");

        Map<String, Object> chapter = mapOf(
            "title", "第1话", "chapterNo", "1", "sortOrder", 0, "globalOrder", 3, "catalogIndex", null,
            "mediaItems", List.of(mapOf(
                "fileName", "001.jpg", "pageNumber", 1, "mediaType", "IMAGE",
                "hqPath", comicId + "/99/001.jpg", "fileSize", 2048, "hqStatus", "READY"))
        );

        List<List<ResolvedMediaItem>> resolved = realResolver.resolveMedia(comicId, List.of(chapter));

        assertEquals(1, resolved.size());
        List<ResolvedMediaItem> items = resolved.get(0);
        assertEquals(1, items.size());
        ResolvedMediaItem item = items.get(0);
        assertEquals(comicId + "/99/001.jpg", item.hqPath(),
                "modern 恢复必须保留 metadata 的真实 hqPath，不得改写为新的 chapterId 目录");
        assertTrue(item.exists(), "hqPath 指向真实存在的文件");
        assertEquals("001.jpg", item.fileName());
        assertEquals(1, item.pageNumber());
    }

    @Test
    void resolveMedia_shouldFallBackToGlobalOrderDirScan_whenNoHqPath() throws Exception {
        // legacy metadata：无 mediaItems/hqPath → 按 globalOrder 目录扫描
        Long comicId = 7700002L;
        Path hqDir = tempDir.resolve("hq").resolve(String.valueOf(comicId)).resolve("7");
        Files.createDirectories(hqDir);
        Files.writeString(hqDir.resolve("002.jpg"), "fake-jpeg-2");
        Files.writeString(hqDir.resolve("001.jpg"), "fake-jpeg-1");

        Map<String, Object> chapter = mapOf(
            "title", "第1话", "chapterNo", "1", "sortOrder", 0, "globalOrder", 7, "catalogIndex", null);

        List<List<ResolvedMediaItem>> resolved = realResolver.resolveMedia(comicId, List.of(chapter));

        List<ResolvedMediaItem> items = resolved.get(0);
        assertEquals(2, items.size(), "legacy 按目录扫描出全部媒体");
        // 文件名排序（001 先于 002）
        assertEquals("001.jpg", items.get(0).fileName());
        assertEquals(comicId + "/7/001.jpg", items.get(0).hqPath());
        assertEquals("002.jpg", items.get(1).fileName());
        assertTrue(items.get(0).exists());
    }

    @Test
    void resolveMedia_shouldReturnMissing_whenHqPathFileAbsent() throws Exception {
        // hqPath 指向不存在的文件 → exists=false（恢复时标 MISSING，不得 READY）
        Long comicId = 7700003L;
        Map<String, Object> chapter = mapOf(
            "title", "第1话", "chapterNo", "1", "sortOrder", 0, "globalOrder", 0, "catalogIndex", null,
            "mediaItems", List.of(mapOf(
                "fileName", "001.jpg", "pageNumber", 1, "mediaType", "IMAGE",
                "hqPath", comicId + "/99/001.jpg", "fileSize", 1024, "hqStatus", "READY"))
        );

        List<List<ResolvedMediaItem>> resolved = realResolver.resolveMedia(comicId, List.of(chapter));

        List<ResolvedMediaItem> items = resolved.get(0);
        assertEquals(1, items.size());
        assertFalse(items.get(0).exists(), "缺文件必须识别为缺失，不得标 READY");
    }

    @Test
    void resolveMedia_shouldTypedFail_whenHqPathIsAbsolute() {
        Long comicId = 7700004L;
        Map<String, Object> chapter = mapOf(
            "title", "第1话", "chapterNo", "1", "sortOrder", 0, "globalOrder", 0, "catalogIndex", null,
            "mediaItems", List.of(mapOf(
                "fileName", "001.jpg", "pageNumber", 1, "mediaType", "IMAGE",
                "hqPath", "D:/manga/hq/" + comicId + "/99/001.jpg", "fileSize", 1024, "hqStatus", "READY"))
        );

        try {
            realResolver.resolveMedia(comicId, List.of(chapter));
            org.junit.jupiter.api.Assertions.fail("绝对路径 hqPath 必须 typed-fail");
        } catch (RuntimeException e) {
            assertTrue(e.getMessage().contains("绝对路径"),
                    "错误信息应说明绝对路径被拒绝，实际: " + e.getMessage());
        }
    }

    /** 允许 null 值的 Map 构造（Map.of 禁止 null，metadata 字段常含 null）。 */
    private static Map<String, Object> mapOf(Object... kv) {
        Map<String, Object> map = new java.util.HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put((String) kv[i], kv[i + 1]);
        }
        return map;
    }

    // ======================== 坏索引 typed-fail（事务前校验，不写 DB） ========================

    @Test
    void processComicDir_shouldTypedFail_whenCatalogParentIndexOutOfRange() throws Exception {
        when(comicMapper.selectById(8L)).thenReturn(null);

        Map<String, Object> metadata = Map.of(
            "comic", Map.of("title", "坏索引漫画", "author", "A"),
            "catalogs", List.of(Map.of("title", "目录1", "sortOrder", 0, "parentIndex", 5)),
            "chapters", java.util.Collections.emptyList()
        );

        try (MockedStatic<Files> filesMock = mockStatic(Files.class)) {
            filesMock.when(() -> Files.exists(any(Path.class))).thenReturn(true);
            when(objectMapper.readValue(any(java.io.File.class), any(TypeReference.class)))
                .thenReturn(metadata);

            RecoveryProgressVO result = recoveryEngine.processComicDir(8L, 0);

            assertEquals(1, result.errorComics());
            assertTrue(result.lastError().contains("parentIndex"),
                    "错误信息应包含 parentIndex，实际: " + result.lastError());
            // typed-fail 发生在 DB 写事务之前：事务从未执行
            verify(transactionTemplate, never()).execute(any());
        }
    }

    @Test
    void processComicDir_shouldTypedFail_whenChapterCatalogIndexOutOfRange() throws Exception {
        when(comicMapper.selectById(9L)).thenReturn(null);

        Map<String, Object> metadata = Map.of(
            "comic", Map.of("title", "坏索引漫画", "author", "A"),
            "catalogs", List.of(Map.of("title", "目录1", "sortOrder", 0)),
            "chapters", List.of(Map.of(
                "title", "第1话", "chapterNo", "1", "sortOrder", 0, "globalOrder", 0, "catalogIndex", 3))
        );

        try (MockedStatic<Files> filesMock = mockStatic(Files.class)) {
            filesMock.when(() -> Files.exists(any(Path.class))).thenReturn(true);
            when(objectMapper.readValue(any(java.io.File.class), any(TypeReference.class)))
                .thenReturn(metadata);

            RecoveryProgressVO result = recoveryEngine.processComicDir(9L, 0);

            assertEquals(1, result.errorComics());
            assertTrue(result.lastError().contains("catalogIndex"),
                    "错误信息应包含 catalogIndex，实际: " + result.lastError());
            verify(transactionTemplate, never()).execute(any());
        }
    }
}
