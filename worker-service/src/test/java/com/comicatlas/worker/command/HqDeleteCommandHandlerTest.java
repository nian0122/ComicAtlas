package com.comicatlas.worker.command;

import com.comicatlas.common.event.ManagementCommandRequestedEvent;
import com.comicatlas.worker.exporter.persistence.ExportMedia;
import com.comicatlas.worker.event.ManagementCommandPublisher;
import com.comicatlas.worker.exporter.persistence.ExportMediaMapper;
import com.comicatlas.worker.storage.StorageProperties;
import com.comicatlas.worker.storage.StorageRoot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * HqDeleteCommandHandler 单元测试。
 * <p>
 * COMIC 级删除必须一次 selectByComicId 取回全部页数据按章节分组复用，
 * 不得在循环内对每个章节重复 selectByChapterId（N+1 回归）。
 */
class HqDeleteCommandHandlerTest {

    private final ExportMediaMapper mediaMapper = mock(ExportMediaMapper.class);
    private final StorageProperties storageProperties = mock(StorageProperties.class);
    private final ManagementCommandPublisher publisher = mock(ManagementCommandPublisher.class);
    private final HqDeleteCommandHandler handler =
            new HqDeleteCommandHandler(storageProperties, mediaMapper, publisher);

    private Path tempRoot;
    private Path hqRoot;

    @BeforeEach
    void setUp() throws IOException {
        tempRoot = Files.createTempDirectory("hq-delete-test-");
        hqRoot = Files.createDirectories(tempRoot.resolve("HQ"));
        StorageRoot root = new StorageRoot();
        root.setPath(hqRoot);
        when(storageProperties.getRoots()).thenReturn(Map.of("HQ", root));
    }

    @AfterEach
    void tearDown() throws IOException {
        if (tempRoot != null) {
            try (Stream<Path> walk = Files.walk(tempRoot)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException e) {
                        // 忽略清理失败
                    }
                });
            }
        }
    }

    private static ExportMedia media(Long id, Long chapterId, String hqPath) {
        ExportMedia media = new ExportMedia();
        media.setId(id);
        media.setChapterId(chapterId);
        media.setHqPath(hqPath);
        return media;
    }

    private static ManagementCommandRequestedEvent comicCmd(Long comicId) {
        return new ManagementCommandRequestedEvent(
                UUID.randomUUID(), Instant.now(), 1, 1L, comicId, 1,
                "HQ_DELETE", "COMIC", comicId);
    }

    private static ManagementCommandRequestedEvent chapterCmd(Long chapterId) {
        return new ManagementCommandRequestedEvent(
                UUID.randomUUID(), Instant.now(), 1, 1L, chapterId, 1,
                "HQ_DELETE", "CHAPTER", chapterId);
    }

    // ==================== Test 1: 漫画级不得逐章重复查询（N+1 回归） ====================

    @Test
    void comicScope_doesNotRequeryEachChapter() throws IOException {
        Path chapterDirA = Files.createDirectories(hqRoot.resolve("1/100"));
        Files.writeString(chapterDirA.resolve("001.jpg"), "a");
        Files.writeString(chapterDirA.resolve("002.jpg"), "b");
        Path chapterDirB = Files.createDirectories(hqRoot.resolve("1/200"));
        Files.writeString(chapterDirB.resolve("001.jpg"), "c");

        when(mediaMapper.selectByComicId(1L)).thenReturn(List.of(
                media(11L, 100L, "1/100/001.jpg"),
                media(12L, 100L, "1/100/002.jpg"),
                media(21L, 200L, "1/200/001.jpg")));

        handler.deleteComic(comicCmd(1L));

        // N+1 回归：一次 selectByComicId 取回全部页后，不得对每章重复 selectByChapterId
        verify(mediaMapper).selectByComicId(1L);
        verify(mediaMapper, never()).selectByChapterId(any(Long.class));
        verify(publisher).completed(any(ManagementCommandRequestedEvent.class));
        assertFalse(Files.exists(chapterDirA.resolve("001.jpg")), "第一章 001.jpg 应被删除");
        assertFalse(Files.exists(chapterDirA.resolve("002.jpg")), "第一章 002.jpg 应被删除");
        assertFalse(Files.exists(chapterDirB.resolve("001.jpg")), "第二章 001.jpg 应被删除");
        assertFalse(Files.exists(chapterDirA), "第一章目录应被删除");
        assertFalse(Files.exists(chapterDirB), "第二章目录应被删除");
    }

    // ==================== Test 2: 漫画无页面 → 发布 failed ====================

    @Test
    void comicScope_emptyComic_publishesFailed() {
        when(mediaMapper.selectByComicId(1L)).thenReturn(List.of());

        handler.deleteComic(comicCmd(1L));

        verify(publisher).failed(any(ManagementCommandRequestedEvent.class), any(String.class));
        verify(publisher, never()).completed(any(ManagementCommandRequestedEvent.class));
    }

    // ==================== Test 3: 章节级走 selectByChapterId 删除单章 ====================

    @Test
    void chapterScope_deletesSingleChapterFiles() throws IOException {
        Path chapterDir = Files.createDirectories(hqRoot.resolve("7/42"));
        Path pageFile = chapterDir.resolve("001.jpg");
        Files.writeString(pageFile, "page");

        when(mediaMapper.selectByChapterId(42L)).thenReturn(List.of(
                media(11L, 42L, "7/42/001.jpg")));

        handler.deleteChapter(chapterCmd(42L));

        verify(mediaMapper).selectByChapterId(42L);
        verify(publisher).completed(any(ManagementCommandRequestedEvent.class));
        assertFalse(Files.exists(pageFile), "HQ 文件应被删除");
        assertFalse(Files.exists(chapterDir), "章节目录应被删除");
    }
}
