package com.comicatlas.worker.exporter;

import com.comicatlas.worker.exporter.publisher.ExportArchivePublisher;
import com.comicatlas.worker.exporter.archive.ZipBuilder;
import com.comicatlas.worker.exporter.exception.ExportFileNotFoundException;
import com.comicatlas.worker.exporter.exception.ExportManifestBuildException;
import com.comicatlas.worker.exporter.model.ExportCollectResult;
import com.comicatlas.worker.exporter.model.ExportManifest;
import com.comicatlas.worker.exporter.metadata.MetadataJsonExporter;
import com.comicatlas.worker.exporter.service.ExportService;
import com.comicatlas.worker.exporter.service.impl.ExportServiceImpl;
import com.comicatlas.worker.exporter.collector.ExportCollector;
import com.comicatlas.worker.exporter.resolver.ExportFileResolver;
import com.comicatlas.worker.config.WorkerConfig;
import com.comicatlas.worker.persistence.record.ChapterRecord;
import com.comicatlas.worker.persistence.record.CatalogRecord;
import com.comicatlas.worker.persistence.record.ComicRecord;
import com.comicatlas.worker.persistence.record.MediaRecord;
import com.comicatlas.worker.storage.StorageProperties;
import com.comicatlas.worker.storage.StorageRef;
import com.comicatlas.worker.storage.StorageRoot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExportServiceTest {
    @Test
    void waitingExportCanBeInterruptedWithoutStartingAnotherCollection() throws Exception {
        java.util.concurrent.CountDownLatch firstEntered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch releaseFirst = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Throwable> waitingFailure = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean interruptPreserved = new java.util.concurrent.atomic.AtomicBoolean();
        when(exportCollector.collect(1L)).thenAnswer(invocation -> {
            firstEntered.countDown();
            if (!releaseFirst.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                throw new IllegalStateException("测试等待超时");
            }
            throw new IllegalStateException("测试主动结束首次导出");
        });
        java.util.concurrent.atomic.AtomicReference<Throwable> firstFailure = new java.util.concurrent.atomic.AtomicReference<>();
        Thread first = new Thread(() -> {
            try {
                service.export(1L, 99L);
            } catch (Exception failure) {
                firstFailure.set(failure);
            }
        }, "测试首次导出");
        Thread waiting = new Thread(() -> {
            try {
                service.export(1L, 100L);
            } catch (Exception failure) {
                waitingFailure.set(failure);
                interruptPreserved.set(Thread.currentThread().isInterrupted());
            }
        }, "测试等待导出");
        first.start();
        try {
            assertTrue(firstEntered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            waiting.start();
            waiting.interrupt();
            waiting.join(5000);
            assertFalse(waiting.isAlive());
            assertInstanceOf(java.io.InterruptedIOException.class, waitingFailure.get());
            assertTrue(interruptPreserved.get());
            verify(exportCollector, times(1)).collect(1L);
        } finally {
            releaseFirst.countDown();
            first.join(5000);
            waiting.interrupt();
            waiting.join(5000);
        }
        assertFalse(first.isAlive());
        assertInstanceOf(IllegalStateException.class, firstFailure.get());
    }
    @Test
    void redeliveryVerifiesExistingArtifactWithoutRebuildingOrCollectingMetadataTwice() throws Exception {
        ZipBuilder realBuilder = spy(new ZipBuilder(workerConfig));
        ExportArchivePublisher realPublisher = new ExportArchivePublisher(realBuilder);
        ExportService realService = new ExportServiceImpl(exportCollector, exportFileResolver, realBuilder,
                metadataJsonExporter, storageProperties, workerConfig, realPublisher);
        MediaRecord media = media(1L, 10L, "1/10/001.jpg", 1);
        ExportCollectResult collected = result(comic(1L, "测试标题"), List.of(chapter(10L, "第一章", 1)), List.of(media));
        when(exportCollector.collect(1L)).thenReturn(collected);
        when(metadataJsonExporter.exportJson(collected)).thenReturn("{}");
        when(exportFileResolver.resolve(media)).thenReturn(new StorageRef("HQ", "1/10/001.jpg"));
        writeFile("hq/1/10/001.jpg", "first");
        stubResolverToRoot();
        ExportService.ExportOutput first = realService.export(1L, 99L);
        ExportService.ExportOutput repeated = realService.export(1L, 99L);
        assertEquals(first.taskId(), repeated.taskId());
        assertEquals(first.comicId(), repeated.comicId());
        assertEquals(first.fileName(), repeated.fileName());
        assertEquals(first.size(), repeated.size());
        verify(realBuilder, times(1)).build(any(), any());
        verify(realBuilder, times(1)).verify(any(), any());
        verify(exportCollector, times(2)).collect(1L);
        verify(metadataJsonExporter, times(2)).exportJson(collected);
        verify(metadataJsonExporter, never()).exportJson(anyLong());
        writeFile("hq/1/10/001.jpg", "other");
        assertThrows(com.comicatlas.worker.exporter.exception.ExportPublishConflictException.class,
                () -> realService.export(1L, 99L));
        verify(realBuilder, times(1)).build(any(), any());
        assertTrue(Files.exists(storageProperties.getRoots().get("EXPORT").getPath().resolve(first.fileName())));
    }

    @Test
    void publishFailureCleansBuiltStagingButKeepsSourceFiles() throws Exception {
        ZipBuilder realBuilder = new ZipBuilder(workerConfig);
        ExportService realService = new ExportServiceImpl(exportCollector, exportFileResolver, realBuilder,
                metadataJsonExporter, storageProperties, workerConfig, archivePublisher);
        MediaRecord media = media(1L, 10L, "1/10/001.jpg", 1);
        when(exportCollector.collect(1L)).thenReturn(result(comic(1L, "标题"),
                List.of(chapter(10L, "第一章", 1)), List.of(media)));
        when(metadataJsonExporter.exportJson(any(ExportCollectResult.class))).thenReturn("{}");
        when(exportFileResolver.resolve(media)).thenReturn(new StorageRef("HQ", "1/10/001.jpg"));
        writeFile("hq/1/10/001.jpg", "source");
        stubResolverToRoot();
        when(archivePublisher.publish(anyLong(), any(), any(), any())).thenThrow(new IOException("原子移动失败"));
        assertThrows(IOException.class, () -> realService.export(1L, 99L));
        assertFalse(Files.exists(storageProperties.getRoots().get("EXPORT").getPath().resolve(".staging-99")));
        assertTrue(Files.exists(tempDir.resolve("hq/1/10/001.jpg")));
    }

    @TempDir
    Path tempDir;

    private ExportCollector exportCollector;
    private ExportFileResolver exportFileResolver;
    private ZipBuilder zipBuilder;
    private MetadataJsonExporter metadataJsonExporter;
    private StorageProperties storageProperties;
    private WorkerConfig workerConfig;
    private ExportArchivePublisher archivePublisher;
    private ExportService service;

    @BeforeEach
    void setUp() throws IOException {
        exportCollector = mock(ExportCollector.class);
        exportFileResolver = mock(ExportFileResolver.class);
        zipBuilder = mock(ZipBuilder.class);
        metadataJsonExporter = mock(MetadataJsonExporter.class);
        archivePublisher = mock(ExportArchivePublisher.class);

        storageProperties = new StorageProperties();
        StorageRoot exportRoot = new StorageRoot();
        exportRoot.setPath(tempDir.resolve("export"));
        Files.createDirectories(exportRoot.getPath());
        storageProperties.setRoots(Map.of("EXPORT", exportRoot));

        workerConfig = new WorkerConfig();
        when(archivePublisher.publish(anyLong(), any(), any(), any()))
                .thenReturn(new ExportArchivePublisher.PublishResult("99/out.zip", 1234L));

        service = new ExportServiceImpl(exportCollector, exportFileResolver, zipBuilder,
                metadataJsonExporter, storageProperties, workerConfig, archivePublisher);
    }

    private ComicRecord comic(Long id, String title) {
        ComicRecord c = new ComicRecord();
        c.setId(id);
        c.setTitle(title);
        return c;
    }

    private ChapterRecord chapter(Long id, String title, int globalOrder) {
        ChapterRecord ch = new ChapterRecord();
        ch.setId(id);
        ch.setTitle(title);
        ch.setGlobalOrder(globalOrder);
        return ch;
    }

    private CatalogRecord catalog(Long id, Long parentId, String title) {
        CatalogRecord catalog = new CatalogRecord();
        catalog.setId(id);
        catalog.setParentId(parentId);
        catalog.setTitle(title);
        return catalog;
    }

    private MediaRecord media(Long id, Long chapterId, String hqPath, Integer pageNumber) {
        MediaRecord m = new MediaRecord();
        m.setId(id);
        m.setChapterId(chapterId);
        m.setHqPath(hqPath);
        m.setHqRoot("HQ");
        m.setHqStatus("READY");
        m.setMediaType("IMAGE");
        m.setPageNumber(pageNumber);
        return m;
    }

    private ExportCollectResult result(ComicRecord comic, List<ChapterRecord> chapters, List<MediaRecord> media) {
        return new ExportCollectResult(comic, chapters, List.of(), media, null);
    }

    private ExportCollectResult result(ComicRecord comic, List<ChapterRecord> chapters,
                                       List<CatalogRecord> catalogs, List<MediaRecord> media) {
        return new ExportCollectResult(comic, chapters, catalogs, media, null);
    }

    private void writeFile(String relative, String content) throws IOException {
        Path p = tempDir.resolve(relative);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    private void stubResolverToRoot() {
        when(exportFileResolver.resolveToPath(any(StorageRef.class))).thenAnswer(inv -> {
            StorageRef ref = inv.getArgument(0);
            return tempDir.resolve(ref.rootKey().toLowerCase()).resolve(ref.relativePath());
        });
    }

    @Test
    void export_buildsManifestAndZip_includesHqLqImagesAndVideo() throws Exception {
        MediaRecord imgHq = media(1L, 10L, "1/10/001.jpg", 1);
        MediaRecord imgLq = media(2L, 10L, "1/10/002.jpg", 2);
        imgLq.setHqStatus("DELETED");
        imgLq.setLqRoot("LQ");
        imgLq.setLqPath("1/10/002.webp");
        imgLq.setLqStatus("READY");
        MediaRecord video = media(3L, 10L, "1/10/003.mp4", 3);
        video.setMediaType("VIDEO");
        ExportCollectResult result = result(comic(1L, "测试标题"), List.of(chapter(10L, "第一章", 1)),
                List.of(imgHq, imgLq, video));

        when(exportCollector.collect(1L)).thenReturn(result);
        when(metadataJsonExporter.exportJson(any(ExportCollectResult.class))).thenReturn("{}");
        when(exportFileResolver.resolve(imgHq)).thenReturn(new StorageRef("HQ", "1/10/001.jpg"));
        when(exportFileResolver.resolve(imgLq)).thenReturn(new StorageRef("LQ", "1/10/002.webp"));
        when(exportFileResolver.resolve(video)).thenReturn(new StorageRef("HQ", "1/10/003.mp4"));
        writeFile("hq/1/10/001.jpg", "a");
        writeFile("hq/1/10/003.mp4", "v");
        writeFile("lq/1/10/002.webp", "b");
        stubResolverToRoot();
        when(zipBuilder.build(any(), any())).thenReturn(
                new ZipBuilder.ZipBuildResult(tempDir.resolve("out.zip"), List.of(tempDir.resolve("out.zip")), 1234L));
        when(archivePublisher.publish(anyLong(), any(), any(), any()))
                .thenReturn(new ExportArchivePublisher.PublishResult("99/测试标题_1_20260809_120000.zip", 1234L));

        ExportService.ExportOutput output = service.export(1L, 99L);

        assertEquals(99L, output.taskId());
        assertEquals(1234L, output.size());
        assertEquals("99/测试标题_1_20260809_120000.zip", output.fileName(),
                "fileName 必须为 EXPORT 根相对路径 {taskId}/{base}.zip");

        ArgumentCaptor<ExportManifest> manifestCaptor = ArgumentCaptor.forClass(ExportManifest.class);
        ArgumentCaptor<Path> outputPathCaptor = ArgumentCaptor.forClass(Path.class);
        verify(zipBuilder).build(manifestCaptor.capture(), outputPathCaptor.capture());
        Path stagedOutput = outputPathCaptor.getValue();
        assertEquals(".staging-99", stagedOutput.getParent().getFileName().toString(),
                "构建必须写入 EXPORT/.staging-{taskId} 隐藏任务目录（同文件系统保证 ATOMIC_MOVE 有效）");
        assertTrue(stagedOutput.getFileName().toString().startsWith("测试标题_1_"));
        assertTrue(stagedOutput.getFileName().toString().endsWith(".zip"));

        ArgumentCaptor<Path> finalDirCaptor = ArgumentCaptor.forClass(Path.class);
        verify(archivePublisher).publish(eq(99L), eq(stagedOutput.getParent()), finalDirCaptor.capture(), any());
        assertEquals(storageProperties.getRoots().get("EXPORT").getPath().resolve("99"), finalDirCaptor.getValue(),
                "最终目录必须为 EXPORT/{taskId}");

        ExportManifest manifest = manifestCaptor.getValue();
        assertEquals(result.allMedia().size(), manifest.entries().size(),
                "媒体条目数必须与采集媒体数严格相等，metadata 另计一条");
        assertEquals("第一章/001.jpg", manifest.entries().get(0).targetPath());
        assertEquals(1L, manifest.entries().get(0).sourceSize(), "条目应记录已知源文件大小");
        assertEquals("第一章/002.webp", manifest.entries().get(1).targetPath(), "仅 LQ 图片按 WebP 文件名导出");
        assertEquals(1L, manifest.entries().get(1).sourceSize());
        assertEquals("第一章/003.mp4", manifest.entries().get(2).targetPath(), "视频必须走 HQ");
        assertEquals("{}", manifest.metadataJson());
    }

    @Test
    void export_failsWhenSourceFileMissing() throws Exception {
        MediaRecord m1 = media(1L, 10L, "1/10/001.jpg", 1);
        when(exportCollector.collect(1L)).thenReturn(result(comic(1L, "标题"), List.of(chapter(10L, "第一章", 1)), List.of(m1)));
        when(metadataJsonExporter.exportJson(any(ExportCollectResult.class))).thenReturn("{}");
        when(exportFileResolver.resolve(m1)).thenReturn(new StorageRef("HQ", "1/10/001.jpg"));
        stubResolverToRoot();

        assertThrows(ExportManifestBuildException.class, () -> service.export(1L, 99L),
                "缺失文件必须让整个导出失败，不得跳过");
        verify(zipBuilder, never()).build(any(), any());
    }

    @Test
    void export_failsWhenSourcePathIsDirectory() throws Exception {
        MediaRecord m1 = media(1L, 10L, "1/10/001.jpg", 1);
        when(exportCollector.collect(1L)).thenReturn(result(comic(1L, "标题"), List.of(chapter(10L, "第一章", 1)), List.of(m1)));
        when(metadataJsonExporter.exportJson(any(ExportCollectResult.class))).thenReturn("{}");
        when(exportFileResolver.resolve(m1)).thenReturn(new StorageRef("HQ", "1/10/001.jpg"));
        Files.createDirectories(tempDir.resolve("hq/1/10/001.jpg")); // 目录冒充文件
        stubResolverToRoot();

        assertThrows(ExportManifestBuildException.class, () -> service.export(1L, 99L),
                "目录冒充文件必须让整个导出失败");
        verify(zipBuilder, never()).build(any(), any());
    }

    @Test
    void export_failsWhenSourceFileUnreadable() throws Exception {
        MediaRecord m1 = media(1L, 10L, "1/10/001.jpg", 1);
        when(exportCollector.collect(1L)).thenReturn(result(comic(1L, "标题"), List.of(chapter(10L, "第一章", 1)), List.of(m1)));
        when(metadataJsonExporter.exportJson(any(ExportCollectResult.class))).thenReturn("{}");
        when(exportFileResolver.resolve(m1)).thenReturn(new StorageRef("HQ", "1/10/001.jpg"));
        writeFile("hq/1/10/001.jpg", "a");
        stubResolverToRoot();

        try (MockedStatic<Files> files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            files.when(() -> Files.isReadable(any(Path.class))).thenReturn(false);
            assertThrows(ExportManifestBuildException.class, () -> service.export(1L, 99L),
                    "不可读文件必须让整个导出失败");
        }
        verify(zipBuilder, never()).build(any(), any());
    }

    @Test
    void export_failsOnCaseFoldedDuplicateTargetPath() throws Exception {
        MediaRecord m1 = media(1L, 10L, "1/10/001.jpg", 1);
        MediaRecord m2 = media(2L, 11L, "1/11/001.jpg", 1);
        when(exportCollector.collect(1L)).thenReturn(result(comic(1L, "标题"),
                List.of(chapter(10L, "Vol.1", 1), chapter(11L, "vol.1", 2)), List.of(m1, m2)));
        when(metadataJsonExporter.exportJson(any(ExportCollectResult.class))).thenReturn("{}");
        when(exportFileResolver.resolve(m1)).thenReturn(new StorageRef("HQ", "1/10/001.jpg"));
        when(exportFileResolver.resolve(m2)).thenReturn(new StorageRef("HQ", "1/11/001.jpg"));
        writeFile("hq/1/10/001.jpg", "a");
        writeFile("hq/1/11/001.jpg", "b");
        stubResolverToRoot();

        assertThrows(ExportManifestBuildException.class, () -> service.export(1L, 99L),
                "大小写折叠后冲突的目标路径（Vol.1/001.jpg 与 vol.1/001.jpg）必须拒绝");
        verify(zipBuilder, never()).build(any(), any());
    }

    @Test
    void export_failsWhenEntryExceedsMaxEntrySize() throws Exception {
        workerConfig.getZip().setMaxEntrySize(2L);
        MediaRecord m1 = media(1L, 10L, "1/10/001.jpg", 1);
        when(exportCollector.collect(1L)).thenReturn(result(comic(1L, "标题"), List.of(chapter(10L, "第一章", 1)), List.of(m1)));
        when(metadataJsonExporter.exportJson(any(ExportCollectResult.class))).thenReturn("{}");
        when(exportFileResolver.resolve(m1)).thenReturn(new StorageRef("HQ", "1/10/001.jpg"));
        writeFile("hq/1/10/001.jpg", "aaa"); // 3 字节 > 2
        stubResolverToRoot();

        assertThrows(ExportManifestBuildException.class, () -> service.export(1L, 99L),
                "单条目超过 maxEntrySize 必须失败");
        verify(zipBuilder, never()).build(any(), any());
    }

    @Test
    void export_failsWhenTotalExceedsMaxTotalSize() throws Exception {
        workerConfig.getZip().setMaxTotalSize(4L);
        MediaRecord m1 = media(1L, 10L, "1/10/001.jpg", 1);
        when(exportCollector.collect(1L)).thenReturn(result(comic(1L, "标题"), List.of(chapter(10L, "第一章", 1)), List.of(m1)));
        when(metadataJsonExporter.exportJson(any(ExportCollectResult.class))).thenReturn("{}");
        when(exportFileResolver.resolve(m1)).thenReturn(new StorageRef("HQ", "1/10/001.jpg"));
        writeFile("hq/1/10/001.jpg", "abc"); // 媒体 3 字节 + metadata "{}" 2 字节 = 5 > 4
        stubResolverToRoot();

        assertThrows(ExportManifestBuildException.class, () -> service.export(1L, 99L),
                "metadata + 全部媒体总量超过 maxTotalSize 必须失败");
        verify(zipBuilder, never()).build(any(), any());
    }

    @Test
    void export_wrapsResolverFailurePreservingCause() throws Exception {
        MediaRecord m1 = media(1L, 10L, "1/10/001.jpg", 1);
        when(exportCollector.collect(1L)).thenReturn(result(comic(1L, "标题"), List.of(chapter(10L, "第一章", 1)), List.of(m1)));
        when(metadataJsonExporter.exportJson(any(ExportCollectResult.class))).thenReturn("{}");
        ExportFileNotFoundException original = new ExportFileNotFoundException("HQ 缺失且 LQ 未就绪：media=1");
        when(exportFileResolver.resolve(m1)).thenThrow(original);

        ExportManifestBuildException ex = assertThrows(ExportManifestBuildException.class, () -> service.export(1L, 99L));
        assertSame(original, ex.getCause(), "领域异常必须保留原始 cause");
        verify(zipBuilder, never()).build(any(), any());
    }

    @Test
    void export_rejectsDuplicateChapterPathsInsteadOfRenamingDirectories() throws Exception {
        MediaRecord m1 = media(1L, 10L, "1/10/001.jpg", 1);
        MediaRecord m2 = media(2L, 11L, "1/11/001.jpg", 1);
        when(exportCollector.collect(1L)).thenReturn(result(comic(1L, "标题"),
                List.of(chapter(10L, "同名章", 1), chapter(11L, "同名章", 2)), List.of(m1, m2)));
        when(metadataJsonExporter.exportJson(any(ExportCollectResult.class))).thenReturn("{}");
        when(exportFileResolver.resolve(m1)).thenReturn(new StorageRef("HQ", "1/10/001.jpg"));
        when(exportFileResolver.resolve(m2)).thenReturn(new StorageRef("HQ", "1/11/001.jpg"));
        writeFile("hq/1/10/001.jpg", "a");
        writeFile("hq/1/11/001.jpg", "b");
        stubResolverToRoot();
        assertThrows(ExportManifestBuildException.class, () -> service.export(1L, 99L),
                "相同目录与文件名不能通过改写目录名掩盖，否则无法恢复原始结构");
        verify(zipBuilder, never()).build(any(), any());
    }

    @Test
    void export_restoresNestedCatalogAndChapterDirectoryNames() throws Exception {
        MediaRecord nestedMedia = media(1L, 10L, "1/10/原始文件.jpg", 1);
        MediaRecord siblingMedia = media(2L, 11L, "1/11/封面.png", 1);
        MediaRecord mixedDirectoryMedia = media(3L, 12L, "1/12/扉页.jpg", 1);
        ChapterRecord nestedChapter = chapter(10L, "第01话", 1);
        nestedChapter.setCatalogId(101L);
        ChapterRecord siblingChapter = chapter(11L, "第02话", 2);
        siblingChapter.setCatalogId(102L);
        ChapterRecord mixedDirectoryChapter = chapter(12L, "附录", 3);
        mixedDirectoryChapter.setCatalogId(101L);
        ExportCollectResult collected = result(comic(1L, "测试标题"),
                List.of(nestedChapter, siblingChapter, mixedDirectoryChapter),
                List.of(catalog(100L, null, "第一卷"), catalog(101L, 100L, "附录"),
                        catalog(102L, 100L, "正篇")),
                List.of(nestedMedia, siblingMedia, mixedDirectoryMedia));
        when(exportCollector.collect(1L)).thenReturn(collected);
        when(metadataJsonExporter.exportJson(collected)).thenReturn("{}");
        when(exportFileResolver.resolve(nestedMedia)).thenReturn(new StorageRef("HQ", "1/10/原始文件.jpg"));
        when(exportFileResolver.resolve(siblingMedia)).thenReturn(new StorageRef("HQ", "1/11/封面.png"));
        when(exportFileResolver.resolve(mixedDirectoryMedia)).thenReturn(new StorageRef("HQ", "1/12/扉页.jpg"));
        writeFile("hq/1/10/原始文件.jpg", "a");
        writeFile("hq/1/11/封面.png", "b");
        writeFile("hq/1/12/扉页.jpg", "c");
        stubResolverToRoot();
        when(zipBuilder.build(any(), any())).thenReturn(
                new ZipBuilder.ZipBuildResult(tempDir.resolve("out.zip"), List.of(tempDir.resolve("out.zip")), 3L));

        service.export(1L, 99L);

        ArgumentCaptor<ExportManifest> manifestCaptor = ArgumentCaptor.forClass(ExportManifest.class);
        verify(zipBuilder).build(manifestCaptor.capture(), any());
        assertEquals(List.of("第一卷/附录/第01话/原始文件.jpg", "第一卷/正篇/第02话/封面.png",
                        "第一卷/附录/扉页.jpg"),
                manifestCaptor.getValue().entries().stream().map(ExportManifest.Entry::targetPath).toList(),
                "导出应依据 catalog 父子关系与章节名称恢复原始层级，且不改写文件名");
    }

    @Test
    void export_writesRestoredHierarchyIntoZipArchive() throws Exception {
        MediaRecord media = media(1L, 10L, "1/10/原始文件.jpg", 1);
        ChapterRecord chapter = chapter(10L, "第01话", 1);
        chapter.setCatalogId(101L);
        ExportCollectResult collected = result(comic(1L, "测试标题"), List.of(chapter),
                List.of(catalog(100L, null, "第一卷"), catalog(101L, 100L, "附录")), List.of(media));
        when(exportCollector.collect(1L)).thenReturn(collected);
        when(metadataJsonExporter.exportJson(collected)).thenReturn("{}");
        when(exportFileResolver.resolve(media)).thenReturn(new StorageRef("HQ", "1/10/原始文件.jpg"));
        writeFile("hq/1/10/原始文件.jpg", "image-content");
        stubResolverToRoot();
        ZipBuilder realZipBuilder = new ZipBuilder(workerConfig);
        ExportService realService = new ExportServiceImpl(exportCollector, exportFileResolver, realZipBuilder,
                metadataJsonExporter, storageProperties, workerConfig, new ExportArchivePublisher(realZipBuilder));

        ExportService.ExportOutput output = realService.export(1L, 100L);

        Path archive = storageProperties.getRoots().get("EXPORT").getPath().resolve(output.fileName());
        try (ZipFile zipFile = new ZipFile(archive.toFile())) {
            assertNotNull(zipFile.getEntry("测试标题/第一卷/附录/第01话/原始文件.jpg"),
                    "压缩包中的媒体条目必须带有恢复后的完整层级");
            assertNotNull(zipFile.getEntry("测试标题/metadata.json"));
            assertNotNull(zipFile.getEntry("测试标题/ComicInfo.xml"));
        }
    }

    @Test
    void export_directoryWritesRestoredHierarchyWithoutArchive() throws Exception {
        MediaRecord media = media(1L, 10L, "1/10/原始文件.jpg", 1);
        ChapterRecord chapter = chapter(10L, "第01话", 1);
        chapter.setCatalogId(101L);
        ExportCollectResult collected = result(comic(1L, "测试标题"), List.of(chapter),
                List.of(catalog(100L, null, "第一卷"), catalog(101L, 100L, "附录")), List.of(media));
        when(exportCollector.collect(1L)).thenReturn(collected);
        when(metadataJsonExporter.exportJson(collected)).thenReturn("{}");
        when(exportFileResolver.resolve(media)).thenReturn(new StorageRef("HQ", "1/10/原始文件.jpg"));
        writeFile("hq/1/10/原始文件.jpg", "image-content");
        stubResolverToRoot();
        ZipBuilder realZipBuilder = new ZipBuilder(workerConfig);
        ExportService realService = new ExportServiceImpl(exportCollector, exportFileResolver, realZipBuilder,
                metadataJsonExporter, storageProperties, workerConfig, new ExportArchivePublisher(realZipBuilder));

        ExportService.ExportOutput output = realService.export(1L, 101L, "DIRECTORY");

        Path exportRoot = storageProperties.getRoots().get("EXPORT").getPath();
        Path comicRoot = exportRoot.resolve(output.fileName());
        assertEquals("101/测试标题", output.fileName());
        assertTrue(Files.isDirectory(comicRoot));
        assertEquals("image-content", Files.readString(comicRoot.resolve("第一卷/附录/第01话/原始文件.jpg")));
        assertTrue(Files.isRegularFile(comicRoot.resolve("metadata.json")));
        try (var files = Files.list(exportRoot.resolve("101"))) {
            assertTrue(files.noneMatch(path -> path.getFileName().toString().endsWith(".zip")),
                    "文件夹导出不应生成 ZIP 文件");
        }
    }

    @Test
    void export_publishesTaskDirectoryAtomically_noStagingLeak() throws Exception {
        WorkerConfig realConfig = new WorkerConfig();
        ZipBuilder realBuilder = new ZipBuilder(realConfig);
        ExportArchivePublisher realPublisher = new ExportArchivePublisher(realBuilder);
        ExportService realService = new ExportServiceImpl(exportCollector, exportFileResolver, realBuilder,
                metadataJsonExporter, storageProperties, realConfig, realPublisher);

        MediaRecord m1 = media(1L, 10L, "1/10/001.jpg", 1);
        when(exportCollector.collect(1L)).thenReturn(result(comic(1L, "测试标题"),
                List.of(chapter(10L, "第一章", 1)), List.of(m1)));
        when(metadataJsonExporter.exportJson(any(ExportCollectResult.class))).thenReturn("{}");
        when(exportFileResolver.resolve(m1)).thenReturn(new StorageRef("HQ", "1/10/001.jpg"));
        writeFile("hq/1/10/001.jpg", "a");
        stubResolverToRoot();

        ExportService.ExportOutput output = realService.export(1L, 99L);

        Path exportRoot = storageProperties.getRoots().get("EXPORT").getPath();
        assertTrue(Files.isDirectory(exportRoot.resolve("99")), "最终任务目录必须一次性出现");
        assertFalse(Files.exists(exportRoot.resolve(".staging-99")), "发布后 staging 不得残留");
        assertTrue(output.fileName().startsWith("99/测试标题_1_"), "fileName 必须为 {taskId}/{base}.zip");
        assertTrue(output.fileName().endsWith(".zip"));
        assertTrue(Files.exists(exportRoot.resolve(output.fileName())),
                "fileName 必须能解析到最终目录内的主 .zip");
        long expectedSize;
        try (var stream = Files.list(exportRoot.resolve("99"))) {
            expectedSize = stream.mapToLong(p -> {
                try {
                    return Files.size(p);
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }).sum();
        }
        assertEquals(expectedSize, output.size(), "size 必须等于最终目录全部卷总和");
    }

    @Test
    void export_cleansStaleStagingBeforeRebuild() throws Exception {
        MediaRecord m1 = media(1L, 10L, "1/10/001.jpg", 1);
        when(exportCollector.collect(1L)).thenReturn(result(comic(1L, "标题"), List.of(chapter(10L, "第一章", 1)), List.of(m1)));
        when(metadataJsonExporter.exportJson(any(ExportCollectResult.class))).thenReturn("{}");
        when(exportFileResolver.resolve(m1)).thenReturn(new StorageRef("HQ", "1/10/001.jpg"));
        writeFile("hq/1/10/001.jpg", "a");
        stubResolverToRoot();
        when(zipBuilder.build(any(), any())).thenReturn(
                new ZipBuilder.ZipBuildResult(tempDir.resolve("out.zip"), List.of(tempDir.resolve("out.zip")), 10L));

        Path staleStaging = storageProperties.getRoots().get("EXPORT").getPath().resolve(".staging-99");
        Files.createDirectories(staleStaging);
        Path staleFile = staleStaging.resolve("stale.bin");
        Files.writeString(staleFile, "遗留");

        service.export(1L, 99L);

        assertFalse(Files.exists(staleFile), "每次导出必须从干净 staging 开始，不得复用遗留文件");
    }

    @Test
    void classifyExportError_knownTypes() {
        assertEquals("ZIP_ERROR", service.classifyExportError(new RuntimeException("zip 损坏")));
        assertEquals("STORAGE_ERROR", service.classifyExportError(new RuntimeException("EXPORT 根目录")));
        assertEquals("EXPORT_ERROR", service.classifyExportError(new RuntimeException("其他")));
    }
}
