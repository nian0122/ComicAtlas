package com.comicatlas.api.admin.recovery;

import com.comicatlas.api.admin.dto.RecoveryProgress;
import com.comicatlas.api.comic.entity.Comic;
import com.comicatlas.api.comic.mapper.CatalogMapper;
import com.comicatlas.api.comic.mapper.ChapterMapper;
import com.comicatlas.api.comic.mapper.ComicMapper;
import com.comicatlas.api.comic.mapper.MediaMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

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

    @InjectMocks
    private RecoveryEngine recoveryEngine;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(recoveryEngine, "mangaRoot", "D:/manga");
    }

    // ======================== Comic 已存在 ========================

    @Test
    void processComicDir_shouldReturnSkipped_whenComicExistsInDb() {
        Comic existing = new Comic();
        existing.setId(1L);
        existing.setTitle("Test");
        when(comicMapper.selectById(1L)).thenReturn(existing);

        RecoveryProgress result = recoveryEngine.processComicDir(1L, 5);

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

            RecoveryProgress result = recoveryEngine.processComicDir(2L, 0);

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

            RecoveryProgress result = recoveryEngine.processComicDir(3L, 10);

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

            RecoveryProgress result = recoveryEngine.processComicDir(4L, 0);

            assertEquals(1, result.totalComics());
            assertEquals(0, result.recoveredComics());
            assertEquals(0, result.skippedComics());
            assertEquals(0, result.placeholderComics());
            assertEquals(1, result.errorComics());
            assertNotNull(result.lastError());
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

            RecoveryProgress result = recoveryEngine.processComicDir(5L, 3);

            assertEquals(4, result.totalComics());
            assertEquals(0, result.recoveredComics());
            assertEquals(0, result.skippedComics());
            assertEquals(0, result.placeholderComics());
            assertEquals(1, result.errorComics());
            assertNotNull(result.lastError());
            assertTrue(result.lastError().contains("DB 写入失败"));
        }
    }

    // ======================== 幂等性：重复调用返回一致结果 ========================

    @Test
    void processComicDir_shouldBeIdempotent_forSameComicId() {
        Comic existing = new Comic();
        existing.setId(6L);
        when(comicMapper.selectById(6L)).thenReturn(existing);

        RecoveryProgress first = recoveryEngine.processComicDir(6L, 0);
        RecoveryProgress second = recoveryEngine.processComicDir(6L, 0);

        assertEquals(1, first.skippedComics());
        assertEquals(1, second.skippedComics());
        assertEquals(first.totalComics(), second.totalComics());
    }
}
