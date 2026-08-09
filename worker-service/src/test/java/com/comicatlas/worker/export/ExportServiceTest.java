package com.comicatlas.worker.export;

import com.comicatlas.worker.config.WorkerConfig;
import com.comicatlas.worker.entity.ExportChapter;
import com.comicatlas.worker.entity.ExportComic;
import com.comicatlas.worker.entity.ExportMedia;
import com.comicatlas.worker.storage.ExportFileResolver;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExportServiceTest {

    @TempDir
    Path tempDir;

    private ExportCollector exportCollector;
    private ExportFileResolver exportFileResolver;
    private ZipBuilder zipBuilder;
    private MetadataJsonExporter metadataJsonExporter;
    private StorageProperties storageProperties;
    private WorkerConfig workerConfig;
    private ExportService service;

    @BeforeEach
    void setUp() throws IOException {
        exportCollector = mock(ExportCollector.class);
        exportFileResolver = mock(ExportFileResolver.class);
        zipBuilder = mock(ZipBuilder.class);
        metadataJsonExporter = mock(MetadataJsonExporter.class);

        storageProperties = new StorageProperties();
        StorageRoot exportRoot = new StorageRoot();
        exportRoot.setPath(tempDir.resolve("export"));
        Files.createDirectories(exportRoot.getPath());
        storageProperties.setRoots(Map.of("EXPORT", exportRoot));

        workerConfig = new WorkerConfig();

        service = new ExportService(exportCollector, exportFileResolver, zipBuilder,
                metadataJsonExporter, storageProperties, workerConfig);
    }

    private ExportComic comic(Long id, String title) {
        ExportComic c = new ExportComic();
        c.setId(id);
        c.setTitle(title);
        return c;
    }

    private ExportChapter chapter(Long id, String title, int globalOrder) {
        ExportChapter ch = new ExportChapter();
        ch.setId(id);
        ch.setTitle(title);
        ch.setGlobalOrder(globalOrder);
        return ch;
    }

    private ExportMedia media(Long id, Long chapterId, String hqPath, Integer pageNumber) {
        ExportMedia m = new ExportMedia();
        m.setId(id);
        m.setChapterId(chapterId);
        m.setHqPath(hqPath);
        m.setHqRoot("HQ");
        m.setHqStatus("READY");
        m.setMediaType("IMAGE");
        m.setPageNumber(pageNumber);
        return m;
    }

    private ExportCollectResult result(ExportComic comic, List<ExportChapter> chapters, List<ExportMedia> media) {
        return new ExportCollectResult(comic, chapters, List.of(), media, null);
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
        ExportMedia imgHq = media(1L, 10L, "1/10/001.jpg", 1);
        ExportMedia imgLq = media(2L, 10L, "1/10/002.jpg", 2);
        imgLq.setHqStatus("DELETED");
        imgLq.setLqRoot("LQ");
        imgLq.setLqPath("1/10/002.jpg");
        imgLq.setLqStatus("READY");
        ExportMedia video = media(3L, 10L, "1/10/003.mp4", 3);
        video.setMediaType("VIDEO");
        ExportCollectResult result = result(comic(1L, "测试标题"), List.of(chapter(10L, "第一章", 1)),
                List.of(imgHq, imgLq, video));

        when(exportCollector.collect(1L)).thenReturn(result);
        when(metadataJsonExporter.exportJson(1L)).thenReturn("{}");
        when(exportFileResolver.resolve(imgHq)).thenReturn(new StorageRef("HQ", "1/10/001.jpg"));
        when(exportFileResolver.resolve(imgLq)).thenReturn(new StorageRef("LQ", "1/10/002.jpg"));
        when(exportFileResolver.resolve(video)).thenReturn(new StorageRef("HQ", "1/10/003.mp4"));
        writeFile("hq/1/10/001.jpg", "a");
        writeFile("hq/1/10/003.mp4", "v");
        writeFile("lq/1/10/002.jpg", "b");
        stubResolverToRoot();
        when(zipBuilder.build(any(), any())).thenReturn(
                new ZipBuilder.ZipBuildResult(tempDir.resolve("out.zip"), List.of(tempDir.resolve("out.zip")), 1234L));

        ExportService.ExportOutput output = service.export(1L, 99L);

        assertEquals(99L, output.taskId());
        assertEquals(1234L, output.size());
        assertTrue(output.fileName().startsWith("测试标题_1_"), "输出文件名应含清理后标题+comicId");
        assertTrue(output.fileName().endsWith(".zip"));

        ArgumentCaptor<ExportManifest> manifestCaptor = ArgumentCaptor.forClass(ExportManifest.class);
        verify(zipBuilder).build(manifestCaptor.capture(), any());
        ExportManifest manifest = manifestCaptor.getValue();
        assertEquals(result.allMedia().size(), manifest.entries().size(),
                "媒体条目数必须与采集媒体数严格相等，metadata 另计一条");
        assertEquals("第一章/001.jpg", manifest.entries().get(0).targetPath());
        assertEquals(1L, manifest.entries().get(0).sourceSize(), "条目应记录已知源文件大小");
        assertEquals("第一章/002.jpg", manifest.entries().get(1).targetPath(), "图片 LQ 回退条目按 LQ 文件名");
        assertEquals(1L, manifest.entries().get(1).sourceSize());
        assertEquals("第一章/003.mp4", manifest.entries().get(2).targetPath(), "视频必须走 HQ");
        assertEquals("{}", manifest.metadataJson());
    }

    @Test
    void export_failsWhenSourceFileMissing() throws Exception {
        ExportMedia m1 = media(1L, 10L, "1/10/001.jpg", 1);
        when(exportCollector.collect(1L)).thenReturn(result(comic(1L, "标题"), List.of(chapter(10L, "第一章", 1)), List.of(m1)));
        when(metadataJsonExporter.exportJson(1L)).thenReturn("{}");
        when(exportFileResolver.resolve(m1)).thenReturn(new StorageRef("HQ", "1/10/001.jpg"));
        stubResolverToRoot();

        assertThrows(ExportManifestBuildException.class, () -> service.export(1L, 99L),
                "缺失文件必须让整个导出失败，不得跳过");
        verify(zipBuilder, never()).build(any(), any());
    }

    @Test
    void export_failsWhenSourcePathIsDirectory() throws Exception {
        ExportMedia m1 = media(1L, 10L, "1/10/001.jpg", 1);
        when(exportCollector.collect(1L)).thenReturn(result(comic(1L, "标题"), List.of(chapter(10L, "第一章", 1)), List.of(m1)));
        when(metadataJsonExporter.exportJson(1L)).thenReturn("{}");
        when(exportFileResolver.resolve(m1)).thenReturn(new StorageRef("HQ", "1/10/001.jpg"));
        Files.createDirectories(tempDir.resolve("hq/1/10/001.jpg")); // 目录冒充文件
        stubResolverToRoot();

        assertThrows(ExportManifestBuildException.class, () -> service.export(1L, 99L),
                "目录冒充文件必须让整个导出失败");
        verify(zipBuilder, never()).build(any(), any());
    }

    @Test
    void export_failsWhenSourceFileUnreadable() throws Exception {
        ExportMedia m1 = media(1L, 10L, "1/10/001.jpg", 1);
        when(exportCollector.collect(1L)).thenReturn(result(comic(1L, "标题"), List.of(chapter(10L, "第一章", 1)), List.of(m1)));
        when(metadataJsonExporter.exportJson(1L)).thenReturn("{}");
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
        ExportMedia m1 = media(1L, 10L, "1/10/001.jpg", 1);
        ExportMedia m2 = media(2L, 11L, "1/11/001.jpg", 1);
        when(exportCollector.collect(1L)).thenReturn(result(comic(1L, "标题"),
                List.of(chapter(10L, "Vol.1", 1), chapter(11L, "vol.1", 2)), List.of(m1, m2)));
        when(metadataJsonExporter.exportJson(1L)).thenReturn("{}");
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
        ExportMedia m1 = media(1L, 10L, "1/10/001.jpg", 1);
        when(exportCollector.collect(1L)).thenReturn(result(comic(1L, "标题"), List.of(chapter(10L, "第一章", 1)), List.of(m1)));
        when(metadataJsonExporter.exportJson(1L)).thenReturn("{}");
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
        ExportMedia m1 = media(1L, 10L, "1/10/001.jpg", 1);
        when(exportCollector.collect(1L)).thenReturn(result(comic(1L, "标题"), List.of(chapter(10L, "第一章", 1)), List.of(m1)));
        when(metadataJsonExporter.exportJson(1L)).thenReturn("{}");
        when(exportFileResolver.resolve(m1)).thenReturn(new StorageRef("HQ", "1/10/001.jpg"));
        writeFile("hq/1/10/001.jpg", "abc"); // 媒体 3 字节 + metadata "{}" 2 字节 = 5 > 4
        stubResolverToRoot();

        assertThrows(ExportManifestBuildException.class, () -> service.export(1L, 99L),
                "metadata + 全部媒体总量超过 maxTotalSize 必须失败");
        verify(zipBuilder, never()).build(any(), any());
    }

    @Test
    void export_wrapsResolverFailurePreservingCause() throws Exception {
        ExportMedia m1 = media(1L, 10L, "1/10/001.jpg", 1);
        when(exportCollector.collect(1L)).thenReturn(result(comic(1L, "标题"), List.of(chapter(10L, "第一章", 1)), List.of(m1)));
        when(metadataJsonExporter.exportJson(1L)).thenReturn("{}");
        ExportFileNotFoundException original = new ExportFileNotFoundException("HQ 缺失且 LQ 未就绪：media=1");
        when(exportFileResolver.resolve(m1)).thenThrow(original);

        ExportManifestBuildException ex = assertThrows(ExportManifestBuildException.class, () -> service.export(1L, 99L));
        assertSame(original, ex.getCause(), "领域异常必须保留原始 cause");
        verify(zipBuilder, never()).build(any(), any());
    }

    @Test
    void export_deduplicatesChapterDirs() throws Exception {
        ExportMedia m1 = media(1L, 10L, "1/10/001.jpg", 1);
        ExportMedia m2 = media(2L, 11L, "1/11/001.jpg", 1);
        when(exportCollector.collect(1L)).thenReturn(result(comic(1L, "标题"),
                List.of(chapter(10L, "同名章", 1), chapter(11L, "同名章", 2)), List.of(m1, m2)));
        when(metadataJsonExporter.exportJson(1L)).thenReturn("{}");
        when(exportFileResolver.resolve(m1)).thenReturn(new StorageRef("HQ", "1/10/001.jpg"));
        when(exportFileResolver.resolve(m2)).thenReturn(new StorageRef("HQ", "1/11/001.jpg"));
        writeFile("hq/1/10/001.jpg", "a");
        writeFile("hq/1/11/001.jpg", "b");
        stubResolverToRoot();
        when(zipBuilder.build(any(), any())).thenReturn(
                new ZipBuilder.ZipBuildResult(tempDir.resolve("out.zip"), List.of(tempDir.resolve("out.zip")), 10L));

        service.export(1L, 99L);

        ArgumentCaptor<ExportManifest> manifestCaptor = ArgumentCaptor.forClass(ExportManifest.class);
        verify(zipBuilder).build(manifestCaptor.capture(), any());
        assertEquals(2, manifestCaptor.getValue().entries().size());
        assertEquals("同名章/001.jpg", manifestCaptor.getValue().entries().get(0).targetPath());
        assertEquals("同名章(1)/001.jpg", manifestCaptor.getValue().entries().get(1).targetPath(),
                "同名章节目录应去重为 同名章(1)");
    }

    @Test
    void classifyExportError_knownTypes() {
        assertEquals("ZIP_ERROR", service.classifyExportError(new RuntimeException("zip 损坏")));
        assertEquals("STORAGE_ERROR", service.classifyExportError(new RuntimeException("EXPORT 根目录")));
        assertEquals("EXPORT_ERROR", service.classifyExportError(new RuntimeException("其他")));
    }
}
